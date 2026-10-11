#!/usr/bin/env bash
# Fixture tests for check-simulation-readiness.sh: one ready lane plus one
# failing lane per readiness scenario. Runs without Docker or services by
# supplying fixture checkpoint files.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checker="$script_dir/check-simulation-readiness.sh"

fixtures_dir="$(mktemp -d)"
trap 'rm -rf "$fixtures_dir"' EXIT

digest_a="$(printf 'a%.0s' {1..64})"
digest_b="$(printf 'b%.0s' {1..64})"

write_checkpoint() {
  local path="$1"
  local digest="$2"
  local drained="$3"
  local outbox="$4"
  cat > "$path" <<EOF
{
  "caseKey": "11111111-1111-1111-1111-111111111111",
  "manifestDigest": "$digest",
  "revision": 0,
  "logicalTime": "2026-10-07T00:00:00Z",
  "gameId": null,
  "state": "ACTIVE",
  "drained": $drained,
  "outboxPending": $outbox,
  "continuationsPending": 0,
  "dueTimersPending": 0,
  "nextDeadline": null,
  "sourceWatermarks": [
    {"groupId": "game-service.session", "topic": "game.events", "partition": 0, "nextOffset": 7}
  ]
}
EOF
}

run_checker() {
  env \
    SIMULATION_LANE="lane-a" \
    SIMULATION_MANIFEST_DIGEST="$digest_a" \
    SIMULATION_GAME_CHECKPOINT_FILE="$fixtures_dir/game.json" \
    SIMULATION_TIMELINE_CHECKPOINT_FILE="$fixtures_dir/timeline.json" \
    "$@" \
    bash "$checker"
}

assert_failure_contains() {
  local expected="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    echo "Expected readiness check to fail" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "Expected failure to contain: $expected" >&2
    echo "Actual failure: $output" >&2
    exit 1
  fi
}

# Ready lane: both services drained on the expected bundle.
write_checkpoint "$fixtures_dir/game.json" "$digest_a" true 0
write_checkpoint "$fixtures_dir/timeline.json" "$digest_a" true 0
ready_output="$(run_checker)"
[[ "$ready_output" == *"checkpoints drained"* ]] \
  || { echo "Expected ready message, got: $ready_output" >&2; exit 1; }
[[ "$ready_output" == *"Downstream barriers still required"* ]] \
  || { echo "Expected watermark barrier notice, got: $ready_output" >&2; exit 1; }
echo "Ready lane passes."

# A service that is not drained fails clearly.
write_checkpoint "$fixtures_dir/game.json" "$digest_a" false 0
write_checkpoint "$fixtures_dir/timeline.json" "$digest_a" true 0
assert_failure_contains "is not drained" run_checker

# Pending outbox work fails clearly.
write_checkpoint "$fixtures_dir/game.json" "$digest_a" true 0
write_checkpoint "$fixtures_dir/timeline.json" "$digest_a" true 1
assert_failure_contains "still reports outboxPending=1" run_checker

# A bundle digest mismatch fails as MANIFEST_MISMATCH.
write_checkpoint "$fixtures_dir/game.json" "$digest_b" true 0
write_checkpoint "$fixtures_dir/timeline.json" "$digest_a" true 0
assert_failure_contains "MANIFEST_MISMATCH" run_checker

# A malformed expected digest fails clearly.
assert_failure_contains "64 lowercase hex" \
  env SIMULATION_MANIFEST_DIGEST="not-a-digest" \
    SIMULATION_GAME_CHECKPOINT_FILE="$fixtures_dir/game.json" \
    SIMULATION_TIMELINE_CHECKPOINT_FILE="$fixtures_dir/timeline.json" \
    bash "$checker"

# A live check without an operator token fails clearly.
assert_failure_contains "missing SIMULATION_OPERATOR_TOKEN" \
  env SIMULATION_MANIFEST_DIGEST="$digest_a" \
    SIMULATION_GAME_URL="http://127.0.0.1:9" \
    SIMULATION_TIMELINE_URL="http://127.0.0.1:9" \
    bash "$checker"

echo "Simulation lane readiness fixtures passed."
