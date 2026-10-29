#!/usr/bin/env bash
# Provisions the deployed simulation workbench E2E (infrastructure#108): the issuer and shared network, N
# isolated simulation lanes that all serve the baseline configuration bundle, an ordinary deployment, the lane
# recycler (recovery only) and the workbench itself. Writes the non-secret deployment descriptor the test harness reads
# (target/workbench-e2e/deployment.json) and the workbench's runtime lane configuration with the lane
# credentials (target/workbench-e2e/lanes.yml, never retained as evidence).
#
# Usage: bash scripts/provision-workbench-e2e.sh
#
# Requires the game-service, timeline-service, read-service and simulation-workbench checkouts as siblings
# of this repository, and MAVEN_SETTINGS (Maven settings with a `github` server entry) for the image builds.
#
# Optional environment:
#   WORKBENCH_E2E_LANES     Number of lanes, at least 1 (default 2). Every lane serves the baseline bundle;
#                           rules variants are overrides the workbench applies per case.
#   WORKBENCH_E2E_RUN_ID    Attribution recorded on every lane (default local).
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
workspace="$(cd "$repo_root/.." && pwd)"
# shellcheck source=lib/workbench-e2e.sh
source "$script_dir/lib/workbench-e2e.sh"
# shellcheck source=lib/simulation-bundle.sh
source "$script_dir/lib/simulation-bundle.sh"
# shellcheck source=lib/workbench-lane-declaration.sh
source "$script_dir/lib/workbench-lane-declaration.sh"

lanes="${WORKBENCH_E2E_LANES:-2}"
run_id="${WORKBENCH_E2E_RUN_ID:-local}"
out="$repo_root/target/workbench-e2e"

fail() { echo "Workbench E2E provisioning failed: $1" >&2; exit 1; }

[[ "$lanes" =~ ^[0-9]+$ && "$lanes" -ge 1 ]] \
  || fail "WORKBENCH_E2E_LANES must be a number of at least 1, got '$lanes'."
for dir in game-service timeline-service read-service simulation-workbench; do
  [[ -d "$workspace/$dir" ]] || fail "sibling checkout not found: $workspace/$dir."
done
[[ -n "${MAVEN_SETTINGS:-}" ]] || fail "MAVEN_SETTINGS must hold the Maven settings used by the image builds."

export WORKBENCH_E2E_REPO="$repo_root"
export WORKBENCH_E2E_WORKSPACE="$workspace"
export SIMULATION_OBSERVER_NETWORK="$workbench_e2e_network"

cd "$repo_root"
rm -rf "$out"
mkdir -p "$out/lanes" "$out/bundles"

echo "== Preparing the baseline configuration bundle"
bash scripts/prepare-simulation-bundle.sh --out "$out/bundles/baseline"
max_eras="$(sed -n 's/^    max-eras: *\([0-9]*\).*/\1/p' "$out/bundles/baseline/application.yml")"
[[ -n "$max_eras" ]] || fail "the baseline bundle declares no game.rules.max-eras."

stack=(docker compose -p "$workbench_e2e_project" -f compose.workbench-e2e.yml)

echo "== Starting the issuer"
"${stack[@]}" up -d issuer
for _ in $(seq 1 60); do
  curl --fail --silent --max-time 5 "$workbench_e2e_issuer_url/.well-known/openid-configuration" > /dev/null && break
  sleep 1
done
curl --fail --silent --max-time 5 "$workbench_e2e_issuer_url/.well-known/openid-configuration" > /dev/null \
  || fail "the issuer is not reachable at $workbench_e2e_issuer_url."

operator_token="$(workbench_e2e_mint workbench-operator simulation:control)"
[[ -n "$operator_token" ]] || fail "the issuer returned no operator token."

lane_env() { printf '%s/lanes/%s.env' "$out" "$1"; }

digest_of() {
  sed -n "s/.*\"$2\": \"\([0-9a-f]*\)\".*/\1/p" "$repo_root/simulation/lanes/$1/manifest.json"
}

echo "== Writing lane manifests"
for k in $(seq 1 "$lanes"); do
  lane="${workbench_e2e_lane_prefix}$k"
  cat > "$(lane_env "$lane")" <<ENV
SIMULATION_LANE=$lane
SIMULATION_EXPERIMENT=workbench-e2e-$run_id
SIMULATION_CASE=workbench-e2e
JWT_ISSUER_URI=$workbench_e2e_issuer_url
SIMULATION_ISSUER_JWKS_URI=$workbench_e2e_jwks_url
SIMULATION_OBSERVER_NETWORK=$workbench_e2e_network
SIMULATION_GAME_PORT=$((18100 + 10 * k))
SIMULATION_TIMELINE_PORT=$((18101 + 10 * k))
SIMULATION_READ_PORT=$((18102 + 10 * k))
SIMULATION_KAFKA_PORT=$((18103 + 10 * k))
SIMULATION_CONFIG_REPO=$out/bundles/baseline
SIMULATION_IMAGE_TAG=wbe2e
ENV
  (
    set -a
    # shellcheck disable=SC1090
    source "$(lane_env "$lane")"
    set +a
    bash scripts/write-simulation-manifest.sh > /dev/null
    SIMULATION_SKIP_ISSUER_REACHABILITY=1 bash scripts/verify-simulation-deployment.sh
  )
  workbench_lane_rules_document "$lane" \
    "$repo_root/simulation/lanes/$lane/effective-rules.json" \
    "/etc/workbench/lanes/$lane/effective-rules.json" \
    "$(digest_of "$lane" effectiveRulesDigest)" > /dev/null \
    || fail "lane $lane: rules-document is unavailable."
done

lane_compose() {
  local lane="$1"
  shift
  (
    set -a
    # shellcheck disable=SC1090
    source "$(lane_env "$lane")"
    set +a
    docker compose -p "$(workbench_e2e_lane_project "$lane")" "${workbench_e2e_lane_files[@]}" "$@"
  )
}

echo "== Building the service images once for every lane"
lane_compose "${workbench_e2e_lane_prefix}1" build game-service timeline-service read-service config-server

echo "== Starting $lanes lanes"
pids=()
for k in $(seq 1 "$lanes"); do
  lane_compose "${workbench_e2e_lane_prefix}$k" up -d --wait --wait-timeout 420 \
    game-service timeline-service read-service kafka-observer &
  pids+=($!)
done
for pid in "${pids[@]}"; do
  wait "$pid" || fail "a lane did not become healthy."
done

# No case has run yet, so every lane service answers the operator's checkpoint read with EXECUTION_NOT_CONFIGURED.
# A service without control operations would answer 404 instead, and the workbench would recycle its lane after
# every case because the case could not be closed there.
echo "== Checking that every lane service serves the control operations"
for k in $(seq 1 "$lanes"); do
  for port in $((18100 + 10 * k)) $((18101 + 10 * k)) $((18102 + 10 * k)); do
    answer="$(curl --silent --max-time 10 -H "Authorization: Bearer $operator_token" \
      "http://127.0.0.1:$port/internal/simulation/v1/checkpoint" -w ' %{http_code}')" || answer="unreachable"
    [[ "$answer" == *'"EXECUTION_NOT_CONFIGURED"'*" 409" ]] \
      || fail "lane ${workbench_e2e_lane_prefix}$k: the service on port $port does not answer EXECUTION_NOT_CONFIGURED before its first case, so it does not serve the control operations (got: $answer)."
  done
done

echo "== Starting the ordinary deployment"
SIMULATION_IMAGE_TAG=wbe2e docker compose -p "$workbench_e2e_ordinary_project" \
  -f compose.yml -f compose.workbench-e2e-ordinary.yml \
  up -d --wait --wait-timeout 420 game-service timeline-service read-service

echo "== Deriving the lane provenance declarations"
image_digest() { docker image inspect "temporal-rift/sim-$1:wbe2e" --format '{{.Id}}'; }
game_image="$(image_digest game-service)"
timeline_image="$(image_digest timeline-service)"
read_image="$(image_digest read-service)"
declarations="$out/declarations"
mkdir -p "$declarations"
for k in $(seq 1 "$lanes"); do
  lane="${workbench_e2e_lane_prefix}$k"
  workbench_lane_declaration "$lane" "$repo_root/simulation/lanes/$lane/manifest.json" \
    "$game_image" "$timeline_image" "$read_image" > "$declarations/$lane.yml" \
    || fail "no valid provenance declaration for lane $lane."
done
for k in $(seq 2 "$lanes"); do
  lane="${workbench_e2e_lane_prefix}$k"
  for other in $(seq 1 $((k - 1))); do
    peer="${workbench_e2e_lane_prefix}$other"
    if [[ "$(digest_of "$lane" effectiveRulesDigest)$(digest_of "$lane" effectiveContentDigest)" \
      == "$(digest_of "$peer" effectiveRulesDigest)$(digest_of "$peer" effectiveContentDigest)" ]]; then
      cmp -s "$declarations/$lane.yml" "$declarations/$peer.yml" \
        || fail "lanes $peer and $lane serve the same bundle but declare different provenance."
      break
    fi
  done
done

echo "== Writing the workbench lane configuration"
{
  printf 'workbench:\n  execution:\n    worker-enabled: true\n    max-attempts-per-case: 1\n    lanes:\n'
  for k in $(seq 1 "$lanes"); do
    lane="${workbench_e2e_lane_prefix}$k"
    printf '      - id: %s\n' "$lane"
    printf '        game-service-url: http://game.%s.lane:8080\n' "$lane"
    printf '        timeline-service-url: http://timeline.%s.lane:8080\n' "$lane"
    printf '        read-service-url: http://read.%s.lane:8080\n' "$lane"
    printf '        operator-token: %s\n' "$operator_token"
    printf '        kafka-bootstrap-servers: kafka.%s.lane:9094\n' "$lane"
    printf '        rules-digest: %s\n' "$(digest_of "$lane" effectiveRulesDigest)"
    rules_document="$(workbench_lane_rules_document \
      "$lane" \
      "$repo_root/simulation/lanes/$lane/effective-rules.json" \
      "/etc/workbench/lanes/$lane/effective-rules.json" \
      "$(digest_of "$lane" effectiveRulesDigest)")" \
      || fail "lane $lane: rules-document is unavailable."
    printf '        rules-document: %s\n' "$rules_document"
    printf '        content-digest: %s\n' "$(digest_of "$lane" effectiveContentDigest)"
    cat "$declarations/$lane.yml"
    printf '        reset-url: http://lane-recycler:8080/lanes/%s/reset\n' "$lane"
    printf '        bots:\n'
    for seat in 0 1 2 3 4; do
      bot="$(workbench_e2e_bot_id "$lane" "$seat")"
      printf '          - player-id: %s\n            token: %s\n' "$bot" "$(workbench_e2e_mint "$bot")"
    done
  done
} > "$out/lanes.yml"

echo "== Writing the deployment descriptor"
{
  printf '{\n  "runId": "%s",\n  "issuer": "%s",\n  "workbenchUrl": "%s",\n' "$run_id" "$workbench_e2e_issuer_url" "$workbench_e2e_workbench_url"
  printf '  "workbenchProject": "%s",\n' "$workbench_e2e_project"
  printf '  "ordinary": {"project": "%s", "gameUrl": "http://127.0.0.1:18300", "timelineUrl": "http://127.0.0.1:18301", "readUrl": "http://127.0.0.1:18302"},\n' "$workbench_e2e_ordinary_project"
  printf '  "maxEras": %s,\n' "$max_eras"
  printf '  "lanes": [\n'
  for k in $(seq 1 "$lanes"); do
    lane="${workbench_e2e_lane_prefix}$k"
    printf '    {"id": "%s", "project": "%s", ' "$lane" "$(workbench_e2e_lane_project "$lane")"
    printf '"gameUrl": "http://127.0.0.1:%s", "timelineUrl": "http://127.0.0.1:%s", "readUrl": "http://127.0.0.1:%s", "kafkaBootstrap": "127.0.0.1:%s", ' \
      "$((18100 + 10 * k))" "$((18101 + 10 * k))" "$((18102 + 10 * k))" "$((18103 + 10 * k))"
    printf '"manifest": "simulation/lanes/%s/manifest.json", "rulesDigest": "%s", "contentDigest": "%s", "bots": [' \
      "$lane" "$(digest_of "$lane" effectiveRulesDigest)" "$(digest_of "$lane" effectiveContentDigest)"
    for seat in 0 1 2 3 4; do
      printf '"%s"' "$(workbench_e2e_bot_id "$lane" "$seat")"
      [[ "$seat" -lt 4 ]] && printf ', '
    done
    printf ']}'
    [[ "$k" -lt "$lanes" ]] && printf ','
    printf '\n'
  done
  printf '  ]\n}\n'
} > "$out/deployment.json"

echo "== Starting the recycler and the workbench"
"${stack[@]}" up -d --build --wait --wait-timeout 600 lane-recycler workbench

echo "Workbench E2E deployment ready: $out/deployment.json"
