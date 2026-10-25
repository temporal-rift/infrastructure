#!/usr/bin/env bash
# Renders the measured results of a deployed workbench E2E run as Markdown: what ran, how long it took, the
# throughput it achieved and the peak resource use observed on the runner. It reports measurements of one
# run on one runner; it makes no performance claim and says nothing about human balance.
#
# Usage: bash scripts/summarize-workbench-e2e.sh <evidence-dir> [resource-samples-file]
#
# Resource samples are lines of `#<epoch seconds>` followed by `name,cpu%,mem-usage / limit` rows per sample.
set -euo pipefail

evidence_dir="${1:?Usage: summarize-workbench-e2e.sh <evidence-dir> [resource-samples-file]}"
samples="${2:-}"

echo "## Deployed workbench E2E measurements"
echo
echo "These are measurements of this run on this runner. They are not a performance claim, and bot results say"
echo "nothing about balance for human players."
echo

shopt -s nullglob
for file in "$evidence_dir"/*.json; do
  suite="$(basename "$file" .json)"
  echo "### Suite: $suite"
  jq -r '
    "- Lanes: \(.lanes | length), all serving the baseline bundle",
    "- Game length served: \(.maxEras) eras, timing mode \(.timingMode)",
    (if .matrixCounts then "- Seed-42 matrix: \(.matrixCounts.requested) requested, \(.matrixCounts.succeeded) succeeded, \(.matrixCounts.failed) failed, \(.matrixCounts.cancelled) cancelled in \(.matrixRuntimeSeconds) s (\(.matrixThroughputCasesPerHour) cases/hour)" else empty end),
    (if .reproduction then "- Reproduction: \(.reproduction.state) in \(.reproduction.seconds) s" else empty end),
    (if .finalCounts then "- Recovery run: \(.retainedAtInterruption) successes retained at interruption; final \(.finalCounts.succeeded) succeeded, \(.finalCounts.cancelled) cancelled of \(.finalCounts.requested) in \(.runtimeSeconds) s (\(.throughputSuccessesPerHour) successes/hour)" else empty end),
    (if .redelivery then "- Redelivered event records: \(.redelivery.recordsRedelivered) over \(.redelivery.cases) cases" else empty end)
  ' "$file" || true
  echo
done

if [[ -n "$samples" && -s "$samples" ]]; then
  echo "### Peak resource use on the runner (sum over all containers)"
  awk -F, '
    function mib(text,   value, unit) {
      split(text, parts, " ")
      value = parts[1]; unit = value; gsub(/[0-9.]/, "", unit); gsub(/[^0-9.]/, "", value)
      if (unit == "GiB") return value * 1024
      if (unit == "KiB") return value / 1024
      if (unit == "B") return value / 1048576
      return value
    }
    /^#/ { if (cpu > peakCpu) peakCpu = cpu; if (mem > peakMem) peakMem = mem; cpu = 0; mem = 0; samples++; next }
    { gsub(/%/, "", $2); cpu += $2; mem += mib($3) }
    END {
      if (cpu > peakCpu) peakCpu = cpu
      if (mem > peakMem) peakMem = mem
      printf "- Samples: %d\n- Peak CPU: %.0f%% of one core\n- Peak memory: %.0f MiB\n", samples, peakCpu, peakMem
    }
  ' "$samples"
fi
