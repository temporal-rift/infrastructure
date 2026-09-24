#!/usr/bin/env bash
# Runs on the host after the e2e-tests run (see .github/workflows/browser-e2e.yml), before
# `down -v` -- unlike the old Maven-bound version, this is no longer a profile execution, since
# e2e-tests' traces/manifest are now bind-mounted to the host (docker-compose.e2e.yml) rather than
# living only inside a container this script would need Docker exec access to.
#
# Bundles Compose logs/container state alongside every scenario's Playwright trace/screenshot and
# the effective manifest into one directory, and refuses to publish it if a bearer token, signed
# JWT, or private key is still present after redaction -- see
# scripts/redact-browser-e2e-diagnostics.py.
#
# Usage: bash scripts/capture-browser-e2e-diagnostics.sh [target-dir]
#
# Overridable so scripts/test-capture-browser-e2e-diagnostics.sh can exercise the bundling and
# redaction logic hermetically, without Docker or a real Compose project. Real runs use the
# defaults.
set -u

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target_dir="${1:-${BROWSER_E2E_DIAGNOSTICS_DIR:-$repo_root/target/browser-e2e-diagnostics}}"
traces_dir="${BROWSER_E2E_TRACES_DIR:-$repo_root/target/browser-e2e-traces}"
manifest_file="${BROWSER_E2E_MANIFEST:-$repo_root/target/browser-e2e-manifest/manifest.json}"

staging_dir="$(mktemp -d)"
flagged_files="$(mktemp)"
zip_list="$(mktemp)"
cleanup() { rm -rf "$staging_dir"; rm -f "$flagged_files" "$zip_list"; return 0; }
trap cleanup EXIT

if [ "${BROWSER_E2E_SKIP_DOCKER:-}" != "1" ]; then
  (cd "$repo_root" && docker compose -f docker-compose.e2e.yml logs --no-color --timestamps \
    >"$staging_dir/compose-logs.txt" 2>&1) || true
  (cd "$repo_root" && docker compose -f docker-compose.e2e.yml ps --all \
    >"$staging_dir/compose-ps.txt" 2>&1) || true
fi

if [ -d "$traces_dir" ]; then
  cp -r "$traces_dir" "$staging_dir/playwright-traces" 2>/dev/null || true
fi

if [ -f "$manifest_file" ]; then
  cp "$manifest_file" "$staging_dir/manifest.json" 2>/dev/null || true
fi

# Redact tokens in both plain files and archived Playwright trace entries while retaining the
# surrounding diagnostics. Refuse private keys and verify the redacted output below. Plain grep
# skips binary files, so the final check scans the contents of every trace archive too.
python_bin=""
for candidate in python3 python; do
  if "$candidate" --version >/dev/null 2>&1; then
    python_bin="$candidate"
    break
  fi
done
if [ -z "$python_bin" ] || ! "$python_bin" "$repo_root/scripts/redact-browser-e2e-diagnostics.py" "$staging_dir"; then
  echo "capture-browser-e2e-diagnostics: could not redact the staged diagnostics." >&2
  exit 1
fi

# The JWT alternative is anchored on the header segment's fixed "eyJ" prefix (base64url of a JSON
# object's leading `{"`) -- an unanchored three-dot-segment pattern also matches ordinary
# dotted package paths in stack traces (e.g. "springframework.transaction.interceptor"),
# redacting diagnostics that were never credentials.
credential_pattern='(Bearer [A-Za-z0-9._-]{20,}|eyJ[A-Za-z0-9_-]{7,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)'
grep -rIlE "$credential_pattern" "$staging_dir" >"$flagged_files" 2>/dev/null || true

find "$staging_dir" -iname '*.zip' >"$zip_list" 2>/dev/null || true
while IFS= read -r zipfile; do
  [ -n "$zipfile" ] || continue
  if unzip -p "$zipfile" 2>/dev/null | grep -aqE "$credential_pattern"; then
    echo "$zipfile (archive contents)" >>"$flagged_files"
  fi
done <"$zip_list"

if [ -s "$flagged_files" ]; then
  echo "capture-browser-e2e-diagnostics: refusing to publish -- a captured file appears to contain a bearer token or private key." >&2
  cat "$flagged_files" >&2
  exit 1
fi

mkdir -p "$target_dir"
cp -r "$staging_dir/." "$target_dir/"
