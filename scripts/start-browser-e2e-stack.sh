#!/usr/bin/env bash
# Brings up the isolated real-browser deployment for the browser-e2e Maven profile
# (infrastructure#47): generates local TLS material and a hosts-file alias the interactive mock
# OIDC issuer needs (see compose.browser-e2e.yml for why), builds the sibling game-client static
# bundle against this deployment, writes and validates the effective manifest with the accelerated
# timing explicitly labeled as a test override, then starts the stack.
#
# Usage: bash scripts/start-browser-e2e-stack.sh
#
# Requires: docker compose v2.24.4+, openssl, a sibling game-client checkout with Node available,
# and (on Linux) passwordless sudo to add the one-line hosts-file alias below.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
workspace_root="$(cd "$repo_root/.." && pwd)"
client_dir="$workspace_root/game-client"
tls_dir="$repo_root/target/browser-e2e-tls"

issuer_host="browser-e2e-auth"
issuer_url="http://${issuer_host}:8080/default"
external_origin="https://localhost:20443"

# If any step below fails, Maven never reaches post-integration-test, so the profile's stop
# execution never runs. Without this trap a failed startup would leave the hosts alias and a
# partially created Compose project behind. Cleared once the issuer reachability check passes.
startup_complete=""
compose_started=""
fail_cleanup() {
  if [ -z "$startup_complete" ]; then
    if [ -n "$compose_started" ]; then
      # Pre-integration-test failures never reach the profile's diagnostics capture, so leave
      # the recent service logs in the job output before the scoped teardown removes the stack.
      echo "Startup failed after the Compose project was started -- dumping recent service logs..."
      docker compose -p temporal-rift-browser-e2e "${compose_files[@]}" logs --no-color --timestamps --tail=200 || true
    fi
    bash "$script_dir/stop-browser-e2e-stack.sh" || true
  fi
}
trap fail_cleanup EXIT

echo "Installing Playwright's Chromium browser..."
# A standalone `mvn` invocation, deliberately not bound as an execution inside the browser-e2e
# profile's own lifecycle: exec-maven-plugin's `java` goal runs the target class in Maven's own
# JVM rather than forking one, and Playwright's CLI driver calls System.exit() once the install
# finishes. Bound in-lifecycle, that silently kills the entire `mvn verify` process right there —
# every later phase (this script, Failsafe, teardown) never runs, yet the step still reports
# success because the CLI's own exit code was 0. A separate process here cannot take the enclosing
# build down with it.
(cd "$repo_root" && mvn --batch-mode --quiet exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.classpathScope=test \
  -Dexec.args="install chromium")

echo "Ensuring ${issuer_host} resolves locally for the interactive mock issuer..."
hosts_marker="$tls_dir/.hosts-entry-added"
mkdir -p "$tls_dir"
if getent hosts "$issuer_host" >/dev/null 2>&1; then
  # Already resolves (a previous run's entry, or something else on this host) — not ours to
  # remove at teardown, so no marker is written.
  rm -f "$hosts_marker"
else
  echo "127.0.0.1 ${issuer_host}" | sudo tee -a /etc/hosts >/dev/null
  # Recorded so stop-browser-e2e-stack.sh removes only the exact entry this run added, never a
  # pre-existing one, and never leaves a privileged alias behind for later unrelated processes.
  touch "$hosts_marker"
fi

echo "Generating local TLS material for the playtest edge..."
mkdir -p "$tls_dir"
if [[ ! -f "$tls_dir/cert.pem" || ! -f "$tls_dir/key.pem" ]]; then
  openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
    -keyout "$tls_dir/key.pem" -out "$tls_dir/cert.pem" \
    -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
fi

echo "Building the game-client static bundle against this deployment..."
(
  cd "$client_dir"
  VITE_API_BASE_URL="$external_origin" \
    VITE_OIDC_ISSUER_URL="$issuer_url" \
    VITE_OIDC_CLIENT_ID="browser-e2e" \
    npm ci
  VITE_API_BASE_URL="$external_origin" \
    VITE_OIDC_ISSUER_URL="$issuer_url" \
    VITE_OIDC_CLIENT_ID="browser-e2e" \
    npm run build
)

export JWT_ISSUER_URI="$issuer_url"
export PLAYTEST_EXTERNAL_ORIGIN="$external_origin"
export PLAYTEST_TLS_CERT="$tls_dir/cert.pem"
export PLAYTEST_TLS_KEY="$tls_dir/key.pem"
export PLAYTEST_TIMING_PRESET="test-override"

echo "Writing the effective deployment manifest..."
bash "$script_dir/write-playtest-manifest.sh" "$repo_root/playtest/manifest.json"

echo "Validating deployment inputs..."
PLAYTEST_SKIP_ISSUER_REACHABILITY=1 bash "$script_dir/verify-playtest-deployment.sh"

compose_files=(-f "$repo_root/compose.yml" -f "$repo_root/compose.playtest.yml" -f "$repo_root/src/test/resources/compose.browser-e2e.yml")

echo "Resetting any stale browser-e2e project..."
docker compose -p temporal-rift-browser-e2e "${compose_files[@]}" down -v --remove-orphans

echo "Starting the browser-e2e deployment..."
compose_started=1
docker compose -p temporal-rift-browser-e2e "${compose_files[@]}" up --build --wait --wait-timeout 420

echo "Confirming the interactive issuer is reachable from the host at ${issuer_url}..."
curl --fail --silent --show-error --max-time 10 "${issuer_url}/.well-known/openid-configuration" >/dev/null

startup_complete=1
trap - EXIT

echo "Browser-e2e deployment is ready at ${external_origin}."
