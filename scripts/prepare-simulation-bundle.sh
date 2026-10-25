#!/usr/bin/env bash
# Creates the configuration bundle a simulation lane serves: a verbatim copy of the baseline config repository.
# A lane serves exactly one bundle (compose.simulation.yml mounts it through SIMULATION_CONFIG_REPO), and its
# effective digests are recorded by write-simulation-manifest.sh. Every lane serves the baseline; a rules variant
# is defined as overrides in the workbench and carried by each case's execution context.
#
# Usage: bash scripts/prepare-simulation-bundle.sh --out DIR
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
source_repo="${SIMULATION_SOURCE_CONFIG_REPO:-$repo_root/config-server/config-repo}"

out=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) out="${2:?--out needs a directory}"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$out" ]] || { echo "--out is required." >&2; exit 2; }
[[ -d "$source_repo" ]] || { echo "Config repository not found: $source_repo" >&2; exit 1; }

rm -rf "$out"
mkdir -p "$out"
cp "$source_repo"/*.yml "$out/"

echo "Simulation bundle written to $out"
