#!/usr/bin/env bash
# Fixture tests for check-simulation-memory-limits.sh. Needs no Docker.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/check-simulation-memory-limits.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cat > "$work/README.md" <<'README'
| `postgres` | 320m | n/a | 250 |
| `kafka` | 640m | 512m | 500 |
| `kafka-topics` | 64m | 128m | 50 |
| `config-server` | 192m | 256m | 150 |
| `game-service` | 480m | 512m | 380 |
| `timeline-service` | 480m | 512m | 380 |
| `read-service` | 480m | 512m | 380 |
| `kafka-observer` | 32m | n/a | 10 |
README

sample() {
  printf 'temporal-rift-sim-wbe2e-1-%s-1,1.0%%,%sMiB / 1024MiB\n' "$1" "$2"
}
write_samples() {
  {
    echo "#1"
    sample postgres 250; sample kafka 500; sample kafka-topics 50; sample config-server 150
    sample game-service 380; sample timeline-service 380; sample read-service "$1"; sample kafka-observer 10
    echo "#2"
    sample read-service 370
  } > "$work/samples.txt"
}

write_samples 380
SIMULATION_README="$work/README.md" bash "$checker" "$work/samples.txt" > /dev/null \
  || { echo "Limits equal to peak plus headroom must pass." >&2; exit 1; }

write_samples 600
if output="$(SIMULATION_README="$work/README.md" bash "$checker" "$work/samples.txt" 2>&1)"; then
  echo "A peak above the documented limit must fail." >&2
  exit 1
fi
[[ "$output" == *"read-service: peak 600MiB needs a 768m limit"* ]] \
  || { echo "The failure must name the container, peak and required limit, got: $output" >&2; exit 1; }

echo "check-simulation-memory-limits.sh checks passed"
