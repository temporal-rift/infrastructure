#!/usr/bin/env bash
# Tears down the browser-e2e deployment, scoped strictly to the `temporal-rift-browser-e2e`
# Compose project so it can never remove another harness's or a developer's own stack.
#
# Usage: bash scripts/stop-browser-e2e-stack.sh
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

docker compose -p temporal-rift-browser-e2e \
  -f "$repo_root/compose.yml" \
  -f "$repo_root/compose.playtest.yml" \
  -f "$repo_root/src/test/resources/compose.browser-e2e.yml" \
  down -v --remove-orphans
