#!/bin/sh
# Bound to the browser-e2e profile's post-integration-test phase, declared before the stack is torn
# down so it still exists to capture from. Runs unconditionally (Maven has no "only on Failsafe
# failure" phase binding) -- the CI workflow uploads the result only on failure.
#
# Bundles Compose logs/container state alongside every scenario's Playwright trace/screenshot
# (already written under target/browser-e2e-traces by the test JVM itself) into one directory, and
# fails loudly if anything bundled looks like it carries a bearer token or signing secret -- a
# credential in a diagnostics artifact is exactly the kind of incidental leak Rule Zero-style
# carelessness produces, so this is checked mechanically rather than trusted by inspection.
set -u

# Overridable so scripts/test-capture-browser-e2e-diagnostics.sh can exercise the bundling and
# redaction logic hermetically, without Docker or a real Compose project. Real runs use the
# defaults, matching this profile's other paths.
target_dir="${BROWSER_E2E_DIAGNOSTICS_DIR:-target/browser-e2e-diagnostics}"
traces_dir="${BROWSER_E2E_TRACES_DIR:-target/browser-e2e-traces}"
manifest_file="${BROWSER_E2E_MANIFEST:-playtest/manifest.json}"
mkdir -p "$target_dir"

if [ "${BROWSER_E2E_SKIP_DOCKER:-}" != "1" ]; then
  compose_files="-f compose.yml -f compose.playtest.yml -f src/test/resources/compose.browser-e2e.yml"

  # Reading logs/state never needs a working issuer or real TLS files -- only *some* value, so
  # Compose's required-variable interpolation doesn't abort the parse before it can find the
  # project's existing containers by label. This execution does not inherit
  # start-browser-e2e-stack.sh's exports (a separate Maven execution is a separate process).
  JWT_ISSUER_URI="${JWT_ISSUER_URI:-http://browser-e2e-auth:8080/default}"
  PLAYTEST_EXTERNAL_ORIGIN="${PLAYTEST_EXTERNAL_ORIGIN:-https://localhost:20443}"
  PLAYTEST_TLS_CERT="${PLAYTEST_TLS_CERT:-target/browser-e2e-tls/cert.pem}"
  PLAYTEST_TLS_KEY="${PLAYTEST_TLS_KEY:-target/browser-e2e-tls/key.pem}"
  export JWT_ISSUER_URI PLAYTEST_EXTERNAL_ORIGIN PLAYTEST_TLS_CERT PLAYTEST_TLS_KEY

  docker compose -p temporal-rift-browser-e2e $compose_files logs --no-color --timestamps \
    >"$target_dir/compose-logs.txt" 2>&1 || true

  docker compose -p temporal-rift-browser-e2e $compose_files ps --all \
    >"$target_dir/compose-ps.txt" 2>&1 || true
fi

if [ -d "$traces_dir" ]; then
  cp -r "$traces_dir" "$target_dir/playwright-traces" 2>/dev/null || true
fi

if [ -f "$manifest_file" ]; then
  cp "$manifest_file" "$target_dir/manifest.json" 2>/dev/null || true
fi

# A bearer token, the mock issuer's signed JWTs, or a private key ever showing up in a bundled
# diagnostics file is a defect in this script, not an acceptable diagnostic detail -- fail the step
# instead of silently uploading it.
if grep -rIlE '(Bearer [A-Za-z0-9._-]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' "$target_dir" >/dev/null 2>&1; then
  echo "capture-browser-e2e-diagnostics: refusing to publish -- a captured file appears to contain a bearer token or private key." >&2
  grep -rIlE '(Bearer [A-Za-z0-9._-]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' "$target_dir" >&2
  exit 1
fi
