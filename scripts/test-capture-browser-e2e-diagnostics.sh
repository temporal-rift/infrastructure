#!/usr/bin/env bash
# Fixture tests for src/test/resources/capture-browser-e2e-diagnostics.sh's credential-redaction
# check: one clean bundle that must pass, one poisoned bundle per credential shape that must fail
# the capture step rather than silently publish it. Runs without Docker, a browser, or a real
# Compose project (BROWSER_E2E_SKIP_DOCKER=1). Mirrors the style of test-verify-playtest-deployment.sh.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
capture_script="$repo_root/src/test/resources/capture-browser-e2e-diagnostics.sh"

fixtures_dir="$(mktemp -d)"
trap 'rm -rf "$fixtures_dir"' EXIT

run_capture() {
  local root="$1"
  BROWSER_E2E_SKIP_DOCKER=1 \
    BROWSER_E2E_DIAGNOSTICS_DIR="$root/out" \
    BROWSER_E2E_TRACES_DIR="$root/traces" \
    BROWSER_E2E_MANIFEST="$root/manifest.json" \
    sh "$capture_script"
}

failures=0
expect_pass() {
  local description="$1"
  shift
  if "$@"; then
    echo "PASS: $description"
  else
    echo "FAIL: $description (expected success, got failure)"
    failures=$((failures + 1))
  fi
}

expect_fail() {
  local description="$1"
  shift
  if "$@"; then
    echo "FAIL: $description (expected failure, capture succeeded)"
    failures=$((failures + 1))
  else
    echo "PASS: $description"
  fi
}

# --- A clean bundle must pass and be left in place for upload. ---
clean_root="$fixtures_dir/clean"
mkdir -p "$clean_root/traces"
echo '{"ok":true}' > "$clean_root/manifest.json"
echo "trace summary: 3 scenarios, 0 failures" > "$clean_root/traces/summary.txt"
expect_pass "a clean diagnostics bundle is published" run_capture "$clean_root"
[ -f "$clean_root/out/playwright-traces/summary.txt" ] || { echo "FAIL: clean bundle did not copy the traces directory"; failures=$((failures + 1)); }

# --- A bearer token anywhere in a captured file must fail the capture step. ---
token_root="$fixtures_dir/token"
mkdir -p "$token_root/traces"
echo '{"ok":true}' > "$token_root/manifest.json"
echo "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.abcdefghijklmnopqrstuvwxyz.signature" > "$token_root/traces/leak.txt"
expect_fail "a bundle containing a bearer token is refused" run_capture "$token_root"
[ ! -e "$token_root/out" ] || { echo "FAIL: a refused bundle must not populate the published diagnostics directory"; failures=$((failures + 1)); }

# --- A PEM private key anywhere in a captured file must fail the capture step. ---
key_root="$fixtures_dir/key"
mkdir -p "$key_root/traces"
echo '{"ok":true}' > "$key_root/manifest.json"
printf -- '-----BEGIN PRIVATE KEY-----\nMIIBVgIBADANBgkqhkiG9w0BAQEFAASCAT8wggE7AgEAAkEA\n-----END PRIVATE KEY-----\n' \
  > "$key_root/traces/leak.txt"
expect_fail "a bundle containing a private key is refused" run_capture "$key_root"
[ ! -e "$key_root/out" ] || { echo "FAIL: a refused bundle must not populate the published diagnostics directory"; failures=$((failures + 1)); }

# --- A credential inside a Playwright trace .zip archive (not just plain text files) must also
# fail the capture step: plain grep skips binary files and would otherwise miss it entirely. ---
zip_root="$fixtures_dir/zip"
mkdir -p "$zip_root/traces"
echo '{"ok":true}' > "$zip_root/manifest.json"
zip_workdir="$(mktemp -d "$fixtures_dir/zip-workdir.XXXXXX")"
echo "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.abcdefghijklmnopqrstuvwxyz.signature" > "$zip_workdir/trace.trace"
python_bin=""
for candidate in python3 python; do
  if "$candidate" --version >/dev/null 2>&1; then
    python_bin="$candidate"
    break
  fi
done
[ -n "$python_bin" ] || { echo "FAIL: no working python interpreter found for the zip-archive fixture"; exit 1; }
"$python_bin" -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1], 'w').write(sys.argv[2], 'trace.trace')" \
  "$zip_root/traces/host-123.zip" "$zip_workdir/trace.trace"
rm -rf "$zip_workdir"
expect_fail "a bundle whose trace archive contains a bearer token is refused" run_capture "$zip_root"
[ ! -e "$zip_root/out" ] || { echo "FAIL: a refused bundle must not populate the published diagnostics directory"; failures=$((failures + 1)); }

# --- A raw JWT (mock issuer token responses carry bare access_token/id_token values with no
# `Bearer` prefix) must also fail the capture step. ---
raw_jwt_root="$fixtures_dir/raw-jwt"
mkdir -p "$raw_jwt_root/traces"
echo '{"ok":true}' > "$raw_jwt_root/manifest.json"
echo "eyJhbGciOiJSUzI1NiJ9.abcdefghijklmnopqrstuvwxyz.signature0" \
  > "$raw_jwt_root/traces/leak.txt"
expect_fail "a bundle containing a raw JWT is refused" run_capture "$raw_jwt_root"
[ ! -e "$raw_jwt_root/out" ] || { echo "FAIL: a refused bundle must not populate the published diagnostics directory"; failures=$((failures + 1)); }

if [ "$failures" -gt 0 ]; then
  echo "$failures fixture check(s) failed."
  exit 1
fi
echo "All capture-browser-e2e-diagnostics fixture checks passed."
