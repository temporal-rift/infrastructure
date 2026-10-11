#!/usr/bin/env bash
# Checks one simulation lane's readiness through both services' checkpoints and their
# per-source/group watermarks. A locally drained checkpoint alone is not global quiescence:
# this check requires both services drained on the same frozen bundle and prints the
# watermarks the runner must additionally observe at the downstream consumers before
# treating the lane as ready.
#
# Usage: bash scripts/check-simulation-readiness.sh
#
# Required environment:
#   SIMULATION_OPERATOR_TOKEN  Bearer token carrying the simulation:control scope.
#                              (Not needed when SIMULATION_GAME_CHECKPOINT_FILE and
#                              SIMULATION_TIMELINE_CHECKPOINT_FILE supply fixtures.)
#   SIMULATION_MANIFEST_DIGEST Expected frozen bundle digest (64 lowercase hex characters).
#                              Defaults to the lane manifest's recorded future-events digest
#                              only when SIMULATION_MANIFEST points at it; set it explicitly
#                              when checking a variant bundle.
#
# Optional environment:
#   SIMULATION_LANE                    Lane name (default: sim-lane).
#   SIMULATION_GAME_URL                Game-service lane origin
#                                     (default: http://127.0.0.1:${SIMULATION_GAME_PORT:-18180}).
#   SIMULATION_TIMELINE_URL            Timeline-service lane origin
#                                     (default: http://127.0.0.1:${SIMULATION_TIMELINE_PORT:-18181}).
#   SIMULATION_GAME_CHECKPOINT_FILE    Fixture checkpoint JSON for game-service (testing only).
#   SIMULATION_TIMELINE_CHECKPOINT_FILE
#                                      Fixture checkpoint JSON for timeline-service (testing only).
#   SIMULATION_MANIFEST                Override for simulation/lanes/<lane>/manifest.json.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

lane="${SIMULATION_LANE:-sim-lane}"
game_url="${SIMULATION_GAME_URL:-http://127.0.0.1:${SIMULATION_GAME_PORT:-18180}}"
timeline_url="${SIMULATION_TIMELINE_URL:-http://127.0.0.1:${SIMULATION_TIMELINE_PORT:-18181}}"
manifest="${SIMULATION_MANIFEST:-$repo_root/simulation/lanes/$lane/manifest.json}"

fail() {
  echo "Simulation lane not ready: $1" >&2
  exit 1
}

json_value() {
  local file="$1"
  local key="$2"
  grep -m1 -o "\"$key\": *\"[^\"]*\"\|\"$key\": *[0-9]*\|\"$key\": *\(true\|false\|null\)" "$file" \
    | sed -e "s#\"$key\": *##" -e 's#"##g' || true
}

fetch_checkpoint() {
  local name="$1"
  local url="$2"
  local fixture_var="$3"
  local out="$4"
  if [[ -n "${!fixture_var:-}" ]]; then
    [[ -f "${!fixture_var}" ]] || fail "fixture checkpoint not found for $name: ${!fixture_var}."
    cp "${!fixture_var}" "$out"
    return
  fi
  [[ -n "${SIMULATION_OPERATOR_TOKEN:-}" ]] \
    || fail "missing SIMULATION_OPERATOR_TOKEN: an operator token with the simulation:control scope is required."
  curl --fail --silent --show-error --max-time 10 \
    -H "Authorization: Bearer $SIMULATION_OPERATOR_TOKEN" \
    "$url/internal/simulation/v1/checkpoint" -o "$out" \
    || fail "$name checkpoint unreachable at $url/internal/simulation/v1/checkpoint."
}

expected_digest="${SIMULATION_MANIFEST_DIGEST:-}"
if [[ -z "$expected_digest" && -f "$manifest" ]]; then
  expected_digest="$(json_value "$manifest" "future-events.yml")"
fi
[[ "$expected_digest" =~ ^[0-9a-f]{64}$ ]] \
  || fail "expected bundle digest must be 64 lowercase hex characters: set SIMULATION_MANIFEST_DIGEST explicitly."

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

fetch_checkpoint "game-service" "$game_url" "SIMULATION_GAME_CHECKPOINT_FILE" "$work_dir/game.json"
fetch_checkpoint "timeline-service" "$timeline_url" "SIMULATION_TIMELINE_CHECKPOINT_FILE" "$work_dir/timeline.json"

for service_file in "$work_dir/game.json" "$work_dir/timeline.json"; do
  digest="$(json_value "$service_file" "manifestDigest")"
  [[ "$digest" == "$expected_digest" ]] \
    || fail "$(basename "$service_file" .json) attests bundle digest '$digest', expected '$expected_digest' (MANIFEST_MISMATCH): regenerate the lane manifest for the served bundle."
  drained="$(json_value "$service_file" "drained")"
  [[ "$drained" == "true" ]] \
    || fail "$(basename "$service_file" .json) is not drained: wait for outbox, continuations, and due timers to complete, then re-check."
  for pending in outboxPending continuationsPending dueTimersPending; do
    count="$(json_value "$service_file" "$pending")"
    [[ "$count" == "0" ]] \
      || fail "$(basename "$service_file" .json) still reports $pending=$count: the lane is not ready."
  done
done

echo "Simulation lane checkpoints drained on bundle $expected_digest (lane=$lane)."
echo "Downstream barriers still required: observe these watermarks at the lane consumers before gameplay."
grep -o '"groupId": *"[^"]*", *"topic": *"[^"]*", *"partition": *[0-9]*, *"nextOffset": *[0-9]*' "$work_dir/game.json" "$work_dir/timeline.json" \
  || echo "(no source watermarks reported)"
