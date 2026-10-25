#!/usr/bin/env bash
# Recovery for a simulation lane whose services are unhealthy or whose execution context cannot be closed; a
# lane plays any number of cases without it. Returns the lane to a clean, ready state: removes only that lane's own Compose
# project (containers, networks, volumes) and starts it again from the images it already has.
# Answers success only once the lane's services report healthy.
#
# Usage: bash scripts/recycle-simulation-lane.sh <lane-env-file>
#
# The env file holds the lane's deployment variables (SIMULATION_LANE, ports, bundle directory, ...),
# exactly the ones used to provision it. A lane's name is the only thing this script ever acts on.
set -euo pipefail

env_file="${1:?Usage: recycle-simulation-lane.sh <lane-env-file>}"
[[ -f "$env_file" ]] || { echo "Lane env file not found: $env_file" >&2; exit 1; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

lane="${SIMULATION_LANE:?The lane env file must set SIMULATION_LANE.}"
[[ "$lane" =~ ^[A-Za-z0-9][A-Za-z0-9-]*$ ]] || { echo "Unsafe lane name: $lane" >&2; exit 1; }
project="temporal-rift-sim-$lane"
# Building is never needed on recycle; the secret only has to exist for Compose to parse the file.
export MAVEN_SETTINGS="${MAVEN_SETTINGS:-<settings/>}"

compose=(docker compose -p "$project" --project-directory "$repo_root"
  -f compose.yml -f compose.simulation.yml -f compose.simulation-observer.yml)

cd "$repo_root"
"${compose[@]}" down -v --remove-orphans
"${compose[@]}" up -d --wait --wait-timeout "${SIMULATION_RECYCLE_TIMEOUT:-300}" \
  game-service timeline-service read-service kafka-observer
echo "Lane $lane recycled (project $project)."
