#!/usr/bin/env bash
# Tears down the browser-e2e deployment, scoped strictly to the `temporal-rift-browser-e2e`
# Compose project so it can never remove another harness's or a developer's own stack.
#
# Usage: bash scripts/stop-browser-e2e-stack.sh
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

# Tearing down never needs a working issuer or real TLS files -- only *some* value, so Compose's
# required-variable interpolation in compose.playtest.yml/compose.browser-e2e.yml doesn't abort the
# parse before `down` can even find the project's existing containers by label. This script may run
# as its own process with none of start-browser-e2e-stack.sh's exports in scope -- a separate CI
# workflow step gets a fresh shell, and even a same-profile Maven execution does not inherit a
# sibling execution's environment -- so real values are never assumed to already be set.
export JWT_ISSUER_URI="${JWT_ISSUER_URI:-http://browser-e2e-auth:8080/default}"
export PLAYTEST_EXTERNAL_ORIGIN="${PLAYTEST_EXTERNAL_ORIGIN:-https://localhost:20443}"
export PLAYTEST_TLS_CERT="${PLAYTEST_TLS_CERT:-$repo_root/target/browser-e2e-tls/cert.pem}"
export PLAYTEST_TLS_KEY="${PLAYTEST_TLS_KEY:-$repo_root/target/browser-e2e-tls/key.pem}"

docker compose -p temporal-rift-browser-e2e \
  -f "$repo_root/compose.yml" \
  -f "$repo_root/compose.playtest.yml" \
  -f "$repo_root/src/test/resources/compose.browser-e2e.yml" \
  down -v --remove-orphans
