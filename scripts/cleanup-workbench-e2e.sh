#!/usr/bin/env bash
# Removes exactly what the deployed workbench E2E created, and nothing else: its lane projects
# (temporal-rift-sim-wbe2e-<n>), the ordinary deployment, the workbench stack and the shared network.
# Every other Compose project, container, volume and network (developer, playtest, E2E, other lanes) is
# left alone, however similar its name.
#
# Usage: bash scripts/cleanup-workbench-e2e.sh [--list]
#   --list  print what would be removed and change nothing
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/workbench-e2e.sh
source "$script_dir/lib/workbench-e2e.sh"

list_only=false
[[ "${1:-}" == "--list" ]] && list_only=true

projects="$(docker compose ls --all --format json | grep -o '"Name":"[^"]*"' | sed 's/"Name":"//; s/"$//' || true)"

# Lanes and the ordinary deployment first: they are attached to the stack's network.
owned=()
for pass in lanes stack; do
  while IFS= read -r project; do
    [[ -z "$project" ]] && continue
    if [[ "$pass" == lanes && ( "$project" =~ ^temporal-rift-sim-wbe2e-[0-9]+$ || "$project" == "$workbench_e2e_ordinary_project" ) ]]        || [[ "$pass" == stack && "$project" == "$workbench_e2e_project" ]]; then
      owned+=("$project")
    fi
  done <<< "$projects"
done

for project in "${owned[@]}"; do
  if $list_only; then
    echo "$project"
  else
    docker compose -p "$project" down -v --remove-orphans
  fi
done

if docker network inspect "$workbench_e2e_network" > /dev/null 2>&1; then
  if $list_only; then
    echo "network:$workbench_e2e_network"
  else
    docker network rm "$workbench_e2e_network" > /dev/null
  fi
fi
