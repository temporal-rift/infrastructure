#!/usr/bin/env bash
# Fixture tests for scripts/capture-browser-e2e-diagnostics.sh's credential handling: bearer
# tokens and JWTs must be redacted, and private keys must block publication. Runs without Docker,
# a browser, or a real Compose project (BROWSER_E2E_SKIP_DOCKER=1). Mirrors the style of
# test-verify-playtest-deployment.sh.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
capture_script="$repo_root/scripts/capture-browser-e2e-diagnostics.sh"

fixtures_dir="$(mktemp -d)"
trap 'rm -rf "$fixtures_dir"' EXIT

run_capture() {
  local root="$1"
  BROWSER_E2E_SKIP_DOCKER=1 \
    BROWSER_E2E_DIAGNOSTICS_DIR="$root/out" \
    BROWSER_E2E_TRACES_DIR="$root/traces" \
    BROWSER_E2E_MANIFEST="$root/manifest.json" \
    bash "$capture_script"
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

# --- A bearer token in a plain captured file must be removed before publication. ---
token_root="$fixtures_dir/token"
mkdir -p "$token_root/traces"
echo '{"ok":true}' > "$token_root/manifest.json"
echo "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.abcdefghijklmnopqrstuvwxyz.signature" > "$token_root/traces/leak.txt"
expect_pass "a bearer token is redacted" run_capture "$token_root"
if ! grep -qF '[REDACTED CREDENTIAL]' "$token_root/out/playwright-traces/leak.txt" ||
  grep -qF 'eyJhbGci' "$token_root/out/playwright-traces/leak.txt"; then
  echo "FAIL: the published bearer token fixture was not redacted"
  failures=$((failures + 1))
fi

# --- A PEM private key anywhere in a captured file must fail the capture step. ---
key_root="$fixtures_dir/key"
mkdir -p "$key_root/traces"
echo '{"ok":true}' > "$key_root/manifest.json"
printf -- '-----BEGIN PRIVATE KEY-----\nMIIBVgIBADANBgkqhkiG9w0BAQEFAASCAT8wggE7AgEAAkEA\n-----END PRIVATE KEY-----\n' \
  > "$key_root/traces/leak.txt"
expect_fail "a bundle containing a private key is refused" run_capture "$key_root"
[ ! -e "$key_root/out" ] || { echo "FAIL: a refused bundle must not populate the published diagnostics directory"; failures=$((failures + 1)); }

# --- A credential inside a Playwright trace .zip archive must be redacted without corrupting the archive. ---
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
expect_pass "a trace archive's bearer token is redacted" run_capture "$zip_root"
if ! unzip -t "$zip_root/out/playwright-traces/host-123.zip" >/dev/null ||
  ! unzip -p "$zip_root/out/playwright-traces/host-123.zip" trace.trace | grep -qF '[REDACTED CREDENTIAL]' ||
  unzip -p "$zip_root/out/playwright-traces/host-123.zip" trace.trace | grep -qF 'eyJhbGci'; then
  echo "FAIL: the redacted trace archive is invalid or still contains its token"
  failures=$((failures + 1))
fi

# --- A raw JWT (mock issuer token responses carry bare access_token/id_token values with no
# `Bearer` prefix) must also be removed. ---
raw_jwt_root="$fixtures_dir/raw-jwt"
mkdir -p "$raw_jwt_root/traces"
echo '{"ok":true}' > "$raw_jwt_root/manifest.json"
echo "eyJhbGciOiJSUzI1NiJ9.abcdefghijklmnopqrstuvwxyz.signature0" \
  > "$raw_jwt_root/traces/leak.txt"
expect_pass "a raw JWT is redacted" run_capture "$raw_jwt_root"
if ! grep -qF '[REDACTED CREDENTIAL]' "$raw_jwt_root/out/playwright-traces/leak.txt" ||
  grep -qF 'eyJhbGci' "$raw_jwt_root/out/playwright-traces/leak.txt"; then
  echo "FAIL: the published raw JWT fixture was not redacted"
  failures=$((failures + 1))
fi

# --- A dotted Java stack-trace package path (three segments, each >= 10 chars) must survive
# intact -- it is not a JWT, and the old unanchored pattern matched it. ---
stacktrace_root="$fixtures_dir/stacktrace"
mkdir -p "$stacktrace_root/traces"
echo '{"ok":true}' > "$stacktrace_root/manifest.json"
echo "at org.springframework.transaction.interceptor.TransactionInterceptor.invoke" \
  > "$stacktrace_root/traces/stacktrace.txt"
expect_pass "a stack trace bundle is published" run_capture "$stacktrace_root"
if ! grep -qF 'springframework.transaction.interceptor' "$stacktrace_root/out/playwright-traces/stacktrace.txt" ||
  grep -qF 'REDACTED' "$stacktrace_root/out/playwright-traces/stacktrace.txt"; then
  echo "FAIL: an ordinary stack trace package path was redacted as if it were a credential"
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures fixture check(s) failed."
  exit 1
fi
echo "All capture-browser-e2e-diagnostics fixture checks passed."
