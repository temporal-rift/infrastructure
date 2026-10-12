#!/usr/bin/env bash
# Captures what is needed to diagnose a failed deployed workbench E2E into one directory: container state,
# the logs of every project the deployment owns, the non-secret deployment descriptor and the lane manifests.
# The runtime lane configuration (target/workbench-e2e/lanes.yml) holds credentials and is never copied.
#
# Usage: bash scripts/capture-workbench-e2e-diagnostics.sh <output-dir>
set -uo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
# shellcheck source=lib/workbench-e2e.sh
source "$script_dir/lib/workbench-e2e.sh"

out="${1:?Usage: capture-workbench-e2e-diagnostics.sh <output-dir>}"
mkdir -p "$out/logs" "$out/manifests"

docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Label "com.docker.compose.project"}}' > "$out/containers.txt" 2>&1
docker compose ls --all > "$out/projects.txt" 2>&1
docker stats --no-stream > "$out/stats.txt" 2>&1

projects="$(docker compose ls --all --format json | grep -o '"Name":"[^"]*"' | sed 's/"Name":"//; s/"$//' || true)"
while IFS= read -r project; do
  if [[ "$project" =~ ^temporal-rift-sim-wbe2e-[0-9]+$ || "$project" == "$workbench_e2e_ordinary_project" || "$project" == "$workbench_e2e_project" ]]; then
    docker compose -p "$project" logs --no-color --timestamps > "$out/logs/$project.log" 2>&1
  fi
done <<< "$projects"

cp "$repo_root/target/workbench-e2e/deployment.json" "$out/" 2>/dev/null || true
for manifest in "$repo_root"/simulation/lanes/"${workbench_e2e_lane_prefix}"*/manifest.json; do
  [[ -f "$manifest" ]] && cp "$manifest" "$out/manifests/$(basename "$(dirname "$manifest")").json"
done
cp -r "$repo_root/target/failsafe-reports" "$out/failsafe-reports" 2>/dev/null || true
exit 0
