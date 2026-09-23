#!/usr/bin/env bash
# Tears down the browser-e2e deployment, scoped strictly to the `temporal-rift-browser-e2e`
# Compose project so it can never remove another harness's or a developer's own stack.
#
# Usage: bash scripts/stop-browser-e2e-stack.sh
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
issuer_host="browser-e2e-auth"
hosts_marker="$repo_root/target/browser-e2e-tls/.hosts-entry-added"

# Runs on any exit path, including a failing `docker compose down` below, so the privileged hosts
# alias never lingers for later unrelated processes on this host just because teardown hit an
# error. Removes only the exact task-owned entry start-browser-e2e-stack.sh recorded adding —
# never a pre-existing entry it found already there.
remove_hosts_alias() {
  if [ -f "$hosts_marker" ]; then
    # GNU sed (this script is documented Linux-only, matching start-browser-e2e-stack.sh).
    sudo sed -i "/^127\.0\.0\.1[[:space:]]\+${issuer_host}\$/d" /etc/hosts
    rm -f "$hosts_marker"
  fi
}
trap remove_hosts_alias EXIT

# Tearing down never needs a working issuer or real TLS files -- only *some* value, so Compose's
# required-variable interpolation in compose.playtest.yml/compose.browser-e2e.yml doesn't abort the
# parse before `down` can even find the project's existing containers by label. This script may run
# as its own process with none of start-browser-e2e-stack.sh's exports in scope -- a separate CI
# workflow step gets a fresh shell, and even a same-profile Maven execution does not inherit a
# sibling execution's environment -- so real values are never assumed to already be set.
export JWT_ISSUER_URI="${JWT_ISSUER_URI:-https://browser-e2e-auth:8080/default}"
export PLAYTEST_EXTERNAL_ORIGIN="${PLAYTEST_EXTERNAL_ORIGIN:-https://localhost:20443}"
export PLAYTEST_TLS_CERT="${PLAYTEST_TLS_CERT:-$repo_root/target/browser-e2e-tls/cert.pem}"
export PLAYTEST_TLS_KEY="${PLAYTEST_TLS_KEY:-$repo_root/target/browser-e2e-tls/key.pem}"

docker compose -p temporal-rift-browser-e2e \
  -f "$repo_root/compose.yml" \
  -f "$repo_root/compose.playtest.yml" \
  -f "$repo_root/src/test/resources/compose.browser-e2e.yml" \
  down -v --remove-orphans
