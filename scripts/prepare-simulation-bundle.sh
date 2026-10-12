#!/usr/bin/env bash
# Creates one configuration bundle for a simulation lane: a copy of the served config repository with
# explicit, verified edits. A lane serves exactly one bundle (compose.simulation.yml mounts it through
# SIMULATION_CONFIG_REPO), and its effective digests are recorded by write-simulation-manifest.sh.
#
# Usage: bash scripts/prepare-simulation-bundle.sh --out DIR [--max-eras N] [--win-score-threshold T]
#
# Every requested edit must change exactly one line of the copy; an edit that matches nothing fails
# instead of silently producing a bundle identical to its source. Without edits the copy is verbatim.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
source_repo="${SIMULATION_SOURCE_CONFIG_REPO:-$repo_root/config-server/config-repo}"

out=""
max_eras=""
threshold=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) out="${2:?--out needs a directory}"; shift 2 ;;
    --max-eras) max_eras="${2:?--max-eras needs a number}"; shift 2 ;;
    --win-score-threshold) threshold="${2:?--win-score-threshold needs a number}"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$out" ]] || { echo "--out is required." >&2; exit 2; }
[[ -d "$source_repo" ]] || { echo "Config repository not found: $source_repo" >&2; exit 1; }

rm -rf "$out"
mkdir -p "$out"
cp "$source_repo"/*.yml "$out/"

# Replaces the value of the single line matching the pattern; fails unless exactly one line matches.
set_value() {
  local file="$1" pattern="$2" replacement="$3"
  local matches
  matches="$(grep -c -E "$pattern" "$file" || true)"
  [[ "$matches" == "1" ]] || { echo "Cannot edit $file: expected one line matching '$pattern', found $matches." >&2; exit 1; }
  sed -i -E "s#$pattern#$replacement#" "$file"
}

if [[ -n "$max_eras" ]]; then
  [[ "$max_eras" =~ ^[1-9][0-9]*$ ]] || { echo "--max-eras must be a positive integer." >&2; exit 2; }
  set_value "$out/application.yml" '^(    max-eras:) [0-9]+[[:space:]]*$' "\1 $max_eras"
fi
if [[ -n "$threshold" ]]; then
  [[ "$threshold" =~ ^[1-9][0-9]*$ ]] || { echo "--win-score-threshold must be a positive integer." >&2; exit 2; }
  set_value "$out/game-service.yml" '^(    win-score-threshold:) [0-9]+[[:space:]]*$' "\1 $threshold"
fi

echo "Simulation bundle written to $out"
