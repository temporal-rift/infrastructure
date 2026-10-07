#!/usr/bin/env bash
# Validates one isolated simulation lane's deployment inputs before any container starts.
# Every failure names the missing or incompatible input; nothing is defaulted
# silently into looking like a reproducible lane.
#
# Usage: bash scripts/verify-simulation-deployment.sh
#
# Required environment:
#   SIMULATION_LANE       Lane name; scopes the task-owned Compose project.
#   SIMULATION_EXPERIMENT Experiment name recorded as lane attribution.
#   SIMULATION_CASE       Case key recorded as lane attribution.
#   JWT_ISSUER_URI        Absolute http(s) URL of the issuer minting lane identities.
#
# Optional environment:
#   SIMULATION_SKIP_ISSUER_REACHABILITY=1 skips the live issuer reachability probe.
#   SIMULATION_COMPOSE_FILE             Override for compose.simulation.yml.
#   SIMULATION_ACLS_SCRIPT              Override for scripts/provision-kafka-simulation-acls.sh.
#   SIMULATION_CONFIG_REPO              Override for the served config-repo directory.
#   SIMULATION_CATALOG                  Override for the served event-catalog file.
#   SIMULATION_MANIFEST                 Override for simulation/lanes/<lane>/manifest.json.
#   SIMULATION_SERVICES_DIR             Directory holding the game-service,
#                                       timeline-service and read-service checkouts.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
workspace_default="$(cd "$repo_root/.." && pwd)"

lane="${SIMULATION_LANE:?SIMULATION_LANE must be set to the lane name.}"
experiment="${SIMULATION_EXPERIMENT:?SIMULATION_EXPERIMENT must be set to the experiment name.}"
case_key="${SIMULATION_CASE:?SIMULATION_CASE must be set to the case key.}"
compose_file="${SIMULATION_COMPOSE_FILE:-$repo_root/compose.simulation.yml}"
acls_script="${SIMULATION_ACLS_SCRIPT:-$repo_root/scripts/provision-kafka-simulation-acls.sh}"
manifest="${SIMULATION_MANIFEST:-$repo_root/simulation/lanes/$lane/manifest.json}"
services_dir="${SIMULATION_SERVICES_DIR:-$workspace_default}"

fail() {
  echo "Simulation lane deployment invalid: $1" >&2
  exit 1
}

is_absolute_http_url() {
  [[ "$1" =~ ^https?://[^[:space:]]+$ ]]
}

# --- Lane attribution and identity inputs ---

[[ "$lane" =~ ^[A-Za-z0-9][A-Za-z0-9-]*$ ]] \
  || fail "SIMULATION_LANE '$lane' must be alphanumeric with dashes: it scopes the task-owned project 'temporal-rift-sim-<lane>'."
[[ -n "$experiment" ]] \
  || fail "missing SIMULATION_EXPERIMENT: set it to the experiment name recorded as lane attribution."
[[ -n "$case_key" ]] \
  || fail "missing SIMULATION_CASE: set it to the case key recorded as lane attribution."

[[ -n "${JWT_ISSUER_URI:-}" ]] \
  || fail "missing JWT_ISSUER_URI: set it to the issuer minting lane bot, operator, and observer identities."
is_absolute_http_url "$JWT_ISSUER_URI" \
  || fail "JWT_ISSUER_URI must be an absolute http(s) URL, got '$JWT_ISSUER_URI'."

if [[ "${SIMULATION_SKIP_ISSUER_REACHABILITY:-}" != "1" ]]; then
  discovery="$JWT_ISSUER_URI/.well-known/openid-configuration"
  issuer_probe="$(mktemp)"
  if ! curl --fail --silent --show-error --max-time 10 "$discovery" -o "$issuer_probe"; then
    rm -f "$issuer_probe"
    fail "JWT_ISSUER_URI is not reachable: discovery document unavailable at $discovery."
  fi
  rm -f "$issuer_probe"
fi

# --- Lane overlay ---

[[ -f "$compose_file" ]] || fail "lane overlay not found: $compose_file."
grep -q "temporal-rift-sim-" "$compose_file" \
  || fail "$compose_file does not scope the task-owned project 'temporal-rift-sim-<lane>'."
grep -q 'SIMULATION_LANE:?' "$compose_file" \
  || fail "$compose_file must require SIMULATION_LANE instead of defaulting to a shared project name."

# Prints the two-space-indented Compose block for one service (from its
# "  <service>:" header to the next top-level key).
service_block() {
  awk -v svc="$1" '
    /^  [A-Za-z0-9_-]+:/ { in_block = ($0 == "  " svc ":") }
    in_block { print }
  ' "$2"
}

for service in postgres kafka kafka-ui zipkin kafka-exporter config-server victorialogs victoriametrics alertmanager vmalert; do
  service_block "$service" "$compose_file" | grep -q "ports: !override \[\]" \
    || fail "$compose_file must unpublish the $service host ports (ports: !override []) so lane traffic stays on the lane's own service ports."
done

for service in game-service timeline-service; do
  service_block "$service" "$compose_file" | grep -q 'GAME_SIMULATION_ENABLED: "true"' \
    || fail "$compose_file must enable the isolated simulation execution mode on $service (GAME_SIMULATION_ENABLED: \"true\"): control operations exist only in simulation deployments."
done

if service_block "read-service" "$compose_file" | grep -q "GAME_SIMULATION_ENABLED"; then
  fail "$compose_file must not enable simulation mode on read-service: it exposes no control operations."
fi

service_block "read-service" "$compose_file" | grep -qE "replicas:[[:space:]]*1([[:space:]]*(#.*)?)?$" \
  || fail "$compose_file must pin read-service to a single replica: one lane runs one case at a time."

grep -q 'JWT_ISSUER_URI:?' "$compose_file" \
  || fail "$compose_file must require JWT_ISSUER_URI instead of falling back to a localhost default."
if grep -q 'JWT_ISSUER_URI:-' "$compose_file"; then
  fail "$compose_file must not define a default JWT_ISSUER_URI fallback: a missing issuer must fail, not silently use localhost."
fi

for service in game-service timeline-service read-service; do
  service_block "$service" "$compose_file" | grep -q "127.0.0.1:" \
    || fail "$compose_file must publish the $service lane port on loopback only: lane APIs are operator-driven, not shared-network entry points."
done

grep -q "profiles: \[local-issuer\]" "$compose_file" \
  || fail "$compose_file must exclude the local interactive issuer from the lane: lanes authenticate against the configured issuer."

for attribution in lane experiment case; do
  grep -q "com.temporal-rift.$attribution" "$compose_file" \
    || fail "$compose_file must label lane resources with com.temporal-rift.$attribution attribution."
done

# Lanes always run on the logical clock driven through the control operations; a
# wall-clock timing/content override in the overlay would silently stop being the
# recorded reproducible mode.
if grep -q "SPRING_APPLICATION_JSON" "$compose_file"; then
  if grep -q -E "hand-deal-forced-types|max-eras|timer-seconds" "$compose_file"; then
    fail "$compose_file carries timing/content overrides: lanes run only on the logical clock recorded as timingMode=LOGICAL, never on an overridden wall-clock ruleset."
  fi
fi

# --- Lane broker grants ---

[[ -f "$acls_script" ]] || fail "lane broker-grant script not found: $acls_script."
# Only actual grant invocations count: the grant() helper and the role comments
# must never satisfy these checks on their own.
grant_lines="$(grep -E '^grant ' "$acls_script" || true)"
[[ -n "$grant_lines" ]] \
  || fail "$acls_script must grant the lane observer its read-only event access."
echo "$grant_lines" | grep -q "game.events" && echo "$grant_lines" | grep -q "timeline.events" \
  || fail "$acls_script must grant the lane observer's game.events and timeline.events reads."
if echo "$grant_lines" | grep -q -E 'bot|operator'; then
  fail "$acls_script must not grant broker access to bot or operator identities: they use only HTTP participant and control APIs."
fi
if echo "$grant_lines" | grep -q '\*'; then
  fail "$acls_script must not contain wildcard broker grants: every lane grant names its topic or group."
fi

# --- Contract compatibility across the lane service set ---

pom_version() {
  grep -m1 -o "<$2>[^<]*</$2>" "$1" 2>/dev/null | sed -e "s#<$2>##" -e "s#</$2>##" || true
}

game_pom="$services_dir/game-service/pom.xml"
timeline_pom="$services_dir/timeline-service/pom.xml"
read_pom="$services_dir/read-service/pom.xml"
for pom in "$game_pom" "$timeline_pom" "$read_pom"; do
  [[ -f "$pom" ]] || fail "service checkout not found at $pom: the lane stack builds sibling services from source."
done

check_shared_pin() {
  local tag="$1"
  shift
  local expected=""
  local pom
  for pom in "$@"; do
    local value
    value="$(pom_version "$pom" "$tag")"
    [[ -n "$value" ]] || fail "unresolvable contract pin $tag in $pom."
    if [[ -z "$expected" ]]; then
      expected="$value"
    elif [[ "$value" != "$expected" ]]; then
      fail "incompatible $tag pins across the lane service set ($expected vs $value in $pom): deploy one compatible contract set."
    fi
  done
}

check_shared_pin "timeline-event.version" "$game_pom" "$timeline_pom" "$read_pom"
check_shared_pin "session-event.version" "$game_pom" "$timeline_pom" "$read_pom"
check_shared_pin "action-event.version" "$game_pom" "$timeline_pom" "$read_pom"
check_shared_pin "scoring-event.version" "$game_pom" "$read_pom"
check_shared_pin "simulation-control-api.version" "$game_pom" "$timeline_pom"

# --- Manifest freshness ---

[[ -f "$manifest" ]] || fail "lane manifest not found at $manifest: run scripts/write-simulation-manifest.sh for this lane."

manifest_value() {
  grep -m1 -o "\"$1\": \"[^\"]*\"" "$manifest" | sed -e "s#\"$1\": \"##" -e 's#"##' || true
}

[[ "$(manifest_value "lane")" == "$lane" ]] \
  || fail "manifest lane '$(manifest_value "lane")' does not match SIMULATION_LANE='$lane': regenerate the manifest for this lane."
[[ "$(manifest_value "experiment")" == "$experiment" ]] \
  || fail "manifest experiment '$(manifest_value "experiment")' does not match SIMULATION_EXPERIMENT='$experiment': regenerate the manifest for this lane."
[[ "$(manifest_value "case")" == "$case_key" ]] \
  || fail "manifest case '$(manifest_value "case")' does not match SIMULATION_CASE='$case_key': regenerate the manifest for this lane."
[[ "$(manifest_value "timingMode")" == "LOGICAL" ]] \
  || fail "manifest timingMode '$(manifest_value "timingMode")' is not LOGICAL: lanes run only on the logical clock."
[[ "$(manifest_value "issuer")" == "$JWT_ISSUER_URI" ]] \
  || fail "manifest issuer '$(manifest_value "issuer")' does not match JWT_ISSUER_URI='$JWT_ISSUER_URI': regenerate the manifest for this lane."
grep -q "simulation:control" "$manifest" \
  || fail "manifest must attribute the simulation:control operator scope."
grep -q "simulation:observe" "$manifest" \
  || fail "manifest must attribute the simulation:observe observer scope."

# The recorded rules/content digests are re-derived from the same inputs the
# writer uses, so a config or catalog change after manifest generation
# fails here instead of deploying under a stale attribution.
config_repo="${SIMULATION_CONFIG_REPO:-$repo_root/config-server/config-repo}"
catalog_file="${SIMULATION_CATALOG:-$services_dir/game-service/src/main/resources/future-events.yml}"

recomputed_digest() {
  local path="$1"
  if [[ -f "$path" ]]; then
    sha256sum "$path" | awk '{print $1}'
  else
    echo "missing"
  fi
}

check_digest_matches() {
  local key="$1"
  local path="$2"
  [[ "$(manifest_value "$key")" == "$(recomputed_digest "$path")" ]] \
    || fail "manifest $key digest does not match $path: regenerate the manifest for this lane (MANIFEST_MISMATCH)."
}

check_digest_matches "application.yml" "$config_repo/application.yml"
check_digest_matches "game-service.yml" "$config_repo/game-service.yml"
check_digest_matches "timeline-service.yml" "$config_repo/timeline-service.yml"
check_digest_matches "read-service.yml" "$config_repo/read-service.yml"
check_digest_matches "future-events.yml" "$catalog_file"

# The retained bundle carries the exact contents the digests attest, so a later
# reader inspects what the lane actually served.
manifest_dir="$(cd "$(dirname "$manifest")" && pwd)"
for retained in application.yml game-service.yml timeline-service.yml read-service.yml future-events.yml; do
  [[ -f "$manifest_dir/bundle/$retained" ]] \
    || fail "retained bundle is missing $retained beside the manifest: regenerate the manifest for this lane."
done

for pin in session-event action-event timeline-event scoring-event session-api action-api scoring-api projection-api chains-api simulation-control-api; do
  case "$pin" in
    session-event|action-event|timeline-event|scoring-event|session-api|action-api|scoring-api)
      expected="$(pom_version "$game_pom" "$pin.version")"
      ;;
    projection-api)
      expected="$(pom_version "$read_pom" "$pin.version")"
      ;;
    chains-api)
      expected="$(pom_version "$timeline_pom" "$pin.version")"
      ;;
    simulation-control-api)
      expected="$(pom_version "$game_pom" "$pin.version")"
      ;;
  esac
  # The shared event pins were already proven equal across services above, so the
  # game-service POM is representative for them; projection-api and chains-api
  # pins live only in their owning service's POM.
  [[ -n "$expected" ]] || fail "unresolvable contract pin $pin.version in the service POMs."
  [[ "$(manifest_value "$pin")" == "$expected" ]] \
    || fail "manifest $pin '$(manifest_value "$pin")' does not match the lane POM pin '$expected': regenerate the manifest for this lane."
done

# --- Score rules ---
# game-service binds game.rules.scoring.score-deltas to its score-reason enum and refuses to
# start with a missing reason. The Expose score is reveal-based: EXPOSE_SIGNATURE_REVEALED +2.
# The retired behavior-change key must not come back under either ruleset.
score_file="$config_repo/game-service.yml"
[[ -f "$score_file" ]] || fail "score rules not found at $score_file."
grep -Eq '^[[:space:]]*EXPOSE_SIGNATURE_REVEALED:[[:space:]]*2([[:space:]]*(#.*)?)?$' "$score_file" \
  || fail "$score_file must supply EXPOSE_SIGNATURE_REVEALED: 2: the reveal-based Expose score delta."
# Unanchored on comment-stripped content so a flow-style reintroduction (e.g. inside
# `score-deltas: {...}`) cannot slip past a line-initial match.
if grep -v '^[[:space:]]*#' "$score_file" | grep -q 'EXPOSE_CHANGED_PLAYER_BEHAVIOR'; then
  fail "$score_file must not contain the retired EXPOSE_CHANGED_PLAYER_BEHAVIOR key: game-service scores Expose via EXPOSE_SIGNATURE_REVEALED."
fi

echo "Simulation lane deployment inputs valid (lane=$lane, timingMode=LOGICAL)."
