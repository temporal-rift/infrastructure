#!/usr/bin/env bash
# Checks the lane memory limits against the peaks measured while the workbench E2E played its cases: each
# container's limit must equal its peak memory plus the documented 25% headroom, rounded up to 32 MiB.
#
# Usage: bash scripts/check-simulation-memory-limits.sh <resource-samples-file>
#
# Resource samples are lines of `#<epoch seconds>` followed by `name,cpu%,mem-usage / limit` rows per sample, as the
# workbench E2E records them. A container is attributed to a lane service by the service name its container name ends
# with (`temporal-rift-sim-<lane>-<service>-<n>`). Limits are read from the README's lane memory table.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
samples="${1:?Usage: check-simulation-memory-limits.sh <resource-samples-file>}"
readme="${SIMULATION_README:-$repo_root/README.md}"
services="postgres kafka kafka-topics config-server game-service timeline-service read-service kafka-observer"

[[ -s "$samples" ]] || { echo "No resource samples in $samples." >&2; exit 1; }

# Peak MiB per service over every sample of every lane container.
peaks="$(awk -F, -v services="$services" '
  function mib(text,   parts, value, unit) {
    split(text, parts, " ")
    value = parts[1]; unit = value; gsub(/[0-9.]/, "", unit); gsub(/[^0-9.]/, "", value)
    if (unit == "GiB") return value * 1024
    if (unit == "KiB") return value / 1024
    if (unit == "B") return value / 1048576
    return value
  }
  BEGIN { count = split(services, names, " ") }
  /^#/ || !/^temporal-rift-sim-/ { next }
  {
    name = $1; sub(/-[0-9]+$/, "", name)
    used = mib($3)
    for (i = 1; i <= count; i++) {
      suffix = "-" names[i]
      # The longest matching service name wins, so kafka-topics is not read as kafka.
      if (substr(name, length(name) - length(suffix) + 1) == suffix && length(names[i]) > length(best[name])) best[name] = names[i]
    }
    service = best[name]
    if (service != "" && used > peak[service]) peak[service] = used
  }
  END { for (service in peak) printf "%s %.0f\n", service, peak[service] }
' "$samples")"

status=0
for service in $services; do
  peak="$(awk -v s="$service" '$1 == s {print $2}' <<< "$peaks")"
  if [[ -z "$peak" ]]; then
    echo "No samples for $service." >&2
    status=1
    continue
  fi
  expected=$(( (peak * 125 / 100 + 31) / 32 * 32 ))
  documented="$(sed -n "s/^| \`$service\` | \([0-9]*\)m |.*/\1/p" "$readme")"
  if [[ "$documented" != "$expected" ]]; then
    echo "$service: peak ${peak}MiB needs a ${expected}m limit, the README states ${documented:-none}m." >&2
    status=1
  else
    echo "$service: peak ${peak}MiB, limit ${expected}m."
  fi
done
exit "$status"
