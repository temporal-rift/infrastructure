#!/usr/bin/env bash
# Fixture tests for verify-simulation-deployment.sh: one passing lane plus one
# failing lane per acceptance scenario. Runs without Docker, services, or an
# OIDC issuer. Mirrors the style of test-verify-playtest-deployment.sh.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
validator="$script_dir/verify-simulation-deployment.sh"
writer="$script_dir/write-simulation-manifest.sh"

fixtures_dir="$(mktemp -d)"
trap 'rm -rf "$fixtures_dir"' EXIT

write_pom() {
  local dir="$1"
  local extra="${2:-}"
  mkdir -p "$dir"
  cat > "$dir/pom.xml" <<EOF
<project>
  <properties>
    <session-event.version>7.0.0</session-event.version>
    <action-event.version>9.3.0</action-event.version>
    <timeline-event.version>7.1.0</timeline-event.version>
    <scoring-event.version>3.0.0</scoring-event.version>
    <session-api.version>3.0.0</session-api.version>
    <action-api.version>7.1.0</action-api.version>
    <scoring-api.version>2.0.1</scoring-api.version>
    <projection-api.version>10.0.0</projection-api.version>
    <chains-api.version>2.0.0</chains-api.version>
    <simulation-control-api.version>1.1.1</simulation-control-api.version>
  </properties>
$extra
</project>
EOF
}

# A complete, internally consistent fixture lane. The lane overlay and the
# broker-grant script under test are the real repository artifacts, not copies.
setup_valid_deployment() {
  local root="$1"
  write_pom "$root/services/game-service"
  write_pom "$root/services/timeline-service"
  write_pom "$root/services/read-service"
  mkdir -p "$root/config-repo"
  echo "game: {}" > "$root/config-repo/application.yml"
  # Minimal served score rules: the validator requires the reveal-based Expose delta and
  # rejects the retired behavior-change key. The digest writer records whatever file is
  # here, so fixtures stay internally consistent.
  cat > "$root/config-repo/game-service.yml" <<'EOF'
game:
  rules:
    scoring:
      score-deltas:
        EXPOSE_SIGNATURE_REVEALED: 2
EOF
  echo "game: {}" > "$root/config-repo/timeline-service.yml"
  echo "game: {}" > "$root/config-repo/read-service.yml"
  echo "events: []" > "$root/catalog.yml"
  SIMULATION_SERVICES_DIR="$root/services" \
    SIMULATION_CONFIG_REPO="$root/config-repo" \
    SIMULATION_CATALOG="$root/catalog.yml" \
    SIMULATION_LANE="lane-a" \
    SIMULATION_EXPERIMENT="exp-1" \
    SIMULATION_CASE="case-1" \
    JWT_ISSUER_URI="https://issuer.example" \
    bash "$writer" "$root/manifest.json" >/dev/null
}

run_validator() {
  local root="$1"
  shift
  local compose_file="${SIMULATION_COMPOSE_FILE:-$repo_root/compose.simulation.yml}"
  local acls_script="${SIMULATION_ACLS_SCRIPT:-$repo_root/scripts/provision-kafka-simulation-acls.sh}"
  local manifest_file="${SIMULATION_MANIFEST:-$root/manifest.json}"
  local lane="${FIX_LANE-lane-a}"
  local experiment="${FIX_EXPERIMENT-exp-1}"
  local case_key="${FIX_CASE-case-1}"
  local jwt="${FIX_JWT-https://issuer.example}"
  env \
    SIMULATION_COMPOSE_FILE="$compose_file" \
    SIMULATION_ACLS_SCRIPT="$acls_script" \
    SIMULATION_MANIFEST="$manifest_file" \
    SIMULATION_SERVICES_DIR="$root/services" \
    SIMULATION_CONFIG_REPO="$root/config-repo" \
    SIMULATION_CATALOG="$root/catalog.yml" \
    SIMULATION_SKIP_ISSUER_REACHABILITY="${SIMULATION_SKIP_ISSUER_REACHABILITY:-1}" \
    SIMULATION_LANE="$lane" \
    SIMULATION_EXPERIMENT="$experiment" \
    SIMULATION_CASE="$case_key" \
    JWT_ISSUER_URI="$jwt" \
    "$@" \
    bash "$validator"
}

assert_failure_contains() {
  local expected="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    echo "Expected simulation validation to fail" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "Expected failure to contain: $expected" >&2
    echo "Actual failure: $output" >&2
    exit 1
  fi
}

# Passing lane against the real overlay and grant script.
valid_root="$fixtures_dir/valid"
setup_valid_deployment "$valid_root"
run_validator "$valid_root"
echo "Valid lane passes."

# Missing lane attribution fails clearly.
FIX_LANE= assert_failure_contains "SIMULATION_LANE" \
  run_validator "$valid_root"

# An unsafe lane name fails clearly.
FIX_LANE="Bad Lane!" assert_failure_contains "alphanumeric" \
  run_validator "$valid_root"

# Missing issuer fails clearly.
FIX_JWT= assert_failure_contains "missing JWT_ISSUER_URI" \
  run_validator "$valid_root"

# Unreachable issuer discovery fails clearly.
SIMULATION_SKIP_ISSUER_REACHABILITY=0 FIX_JWT="http://127.0.0.1:9/unreachable" \
  assert_failure_contains "not reachable" \
  run_validator "$valid_root"

# An overlay without the isolated execution mode fails clearly.
no_sim_root="$fixtures_dir/no-sim"
setup_valid_deployment "$no_sim_root"
sed 's#GAME_SIMULATION_ENABLED: "true"#GAME_SIMULATION_ENABLED: "false"#' \
  "$repo_root/compose.simulation.yml" > "$no_sim_root/compose.yml"
SIMULATION_COMPOSE_FILE="$no_sim_root/compose.yml" assert_failure_contains "must enable the isolated simulation execution mode" \
  run_validator "$no_sim_root"

# Simulation mode on read-service fails clearly: it exposes no control operations.
read_sim_root="$fixtures_dir/read-sim"
setup_valid_deployment "$read_sim_root"
awk '/^  read-service:/ {inread=1} inread && /SPRING_SECURITY_OAUTH2/ {print; print "      GAME_SIMULATION_ENABLED: \"true\""; inread=0; next} /^  [A-Za-z0-9_-]+:/ && $0 != "  read-service:" {inread=0} {print}' \
  "$repo_root/compose.simulation.yml" > "$read_sim_root/compose.yml"
SIMULATION_COMPOSE_FILE="$read_sim_root/compose.yml" assert_failure_contains "must not enable simulation mode on read-service" \
  run_validator "$read_sim_root"

# Non-loopback lane ports fail clearly.
loopback_root="$fixtures_dir/loopback"
setup_valid_deployment "$loopback_root"
sed 's#127.0.0.1:#0.0.0.0:#' \
  "$repo_root/compose.simulation.yml" > "$loopback_root/compose.yml"
SIMULATION_COMPOSE_FILE="$loopback_root/compose.yml" assert_failure_contains "on loopback only" \
  run_validator "$loopback_root"

# Non-single read-service replicas fail clearly.
replica_root="$fixtures_dir/replica"
setup_valid_deployment "$replica_root"
sed 's#replicas: 1#replicas: 2#' \
  "$repo_root/compose.simulation.yml" > "$replica_root/compose.yml"
SIMULATION_COMPOSE_FILE="$replica_root/compose.yml" assert_failure_contains "single replica" \
  run_validator "$replica_root"

# A wall-clock timing override in the overlay fails clearly.
override_root="$fixtures_dir/override"
setup_valid_deployment "$override_root"
{
  echo ""
  echo "  game-service:"
  echo '    environment:'
  echo '      SPRING_APPLICATION_JSON: {"game":{"timers":{"timer-seconds":5}}}'
} >> "$override_root/overlay.yml"
cp "$repo_root/compose.simulation.yml" "$override_root/base-overlay.yml"
cat "$repo_root/compose.simulation.yml" "$override_root/overlay.yml" > "$override_root/compose.yml"
SIMULATION_COMPOSE_FILE="$override_root/compose.yml" assert_failure_contains "only on the logical clock" \
  run_validator "$override_root"

# Incompatible adopted contract set fails clearly.
incompatible_root="$fixtures_dir/incompatible"
setup_valid_deployment "$incompatible_root"
sed -i 's#<timeline-event.version>7.1.0</timeline-event.version>#<timeline-event.version>7.0.0</timeline-event.version>#' \
  "$incompatible_root/services/read-service/pom.xml"
assert_failure_contains "incompatible timeline-event.version pins" \
  run_validator "$incompatible_root"

# A control-contract mismatch between the lane services fails clearly.
control_mismatch_root="$fixtures_dir/control-mismatch"
setup_valid_deployment "$control_mismatch_root"
sed -i 's#<simulation-control-api.version>1.1.1</simulation-control-api.version>#<simulation-control-api.version>1.0.0</simulation-control-api.version>#' \
  "$control_mismatch_root/services/timeline-service/pom.xml"
assert_failure_contains "incompatible simulation-control-api.version pins" \
  run_validator "$control_mismatch_root"

# Stale manifest fails clearly.
stale_root="$fixtures_dir/stale"
setup_valid_deployment "$stale_root"
sed -i 's#<action-api.version>7.1.0</action-api.version>#<action-api.version>7.2.0</action-api.version>#' \
  "$stale_root/services/game-service/pom.xml"
assert_failure_contains "does not match the lane POM pin" \
  run_validator "$stale_root"

# Manifest generated for a different lane fails clearly.
lane_root="$fixtures_dir/lane"
setup_valid_deployment "$lane_root"
FIX_LANE="lane-b" assert_failure_contains "does not match SIMULATION_LANE" \
  run_validator "$lane_root"

# Manifest generated for a different issuer fails clearly.
issuer_root="$fixtures_dir/issuer"
setup_valid_deployment "$issuer_root"
SIMULATION_SERVICES_DIR="$issuer_root/services" \
  SIMULATION_CONFIG_REPO="$issuer_root/config-repo" \
  SIMULATION_CATALOG="$issuer_root/catalog.yml" \
  SIMULATION_LANE="lane-a" \
  SIMULATION_EXPERIMENT="exp-1" \
  SIMULATION_CASE="case-1" \
  JWT_ISSUER_URI="https://other-issuer.example" \
  bash "$writer" "$issuer_root/manifest.json" >/dev/null
assert_failure_contains "does not match JWT_ISSUER_URI" \
  run_validator "$issuer_root"

# A non-logical timing mode fails clearly.
timing_root="$fixtures_dir/timing"
setup_valid_deployment "$timing_root"
sed -i 's#"timingMode": "LOGICAL"#"timingMode": "wall-clock"#' "$timing_root/manifest.json"
assert_failure_contains "is not LOGICAL" \
  run_validator "$timing_root"

# Rules/content drift after manifest generation fails clearly.
drift_root="$fixtures_dir/drift"
setup_valid_deployment "$drift_root"
echo "game: {drifted: true}" >> "$drift_root/config-repo/game-service.yml"
assert_failure_contains "MANIFEST_MISMATCH" \
  run_validator "$drift_root"

# A missing retained bundle fails clearly.
bundle_root="$fixtures_dir/bundle"
setup_valid_deployment "$bundle_root"
rm "$bundle_root/bundle/application.yml"
SIMULATION_MANIFEST="$bundle_root/manifest.json" assert_failure_contains "retained bundle is missing" \
  run_validator "$bundle_root"

# Missing reveal-based Expose score delta fails clearly, without touching the manifest
# digests: rewrite the served file and the manifest together so only the score check fails.
expose_missing_root="$fixtures_dir/expose-missing"
setup_valid_deployment "$expose_missing_root"
cat > "$expose_missing_root/config-repo/game-service.yml" <<'EOF'
game:
  rules:
    scoring:
      score-deltas:
        CHAIN_COMPLETED: 10
EOF
SIMULATION_SERVICES_DIR="$expose_missing_root/services" \
  SIMULATION_CONFIG_REPO="$expose_missing_root/config-repo" \
  SIMULATION_CATALOG="$expose_missing_root/catalog.yml" \
  SIMULATION_LANE="lane-a" \
  SIMULATION_EXPERIMENT="exp-1" \
  SIMULATION_CASE="case-1" \
  JWT_ISSUER_URI="https://issuer.example" \
  bash "$writer" "$expose_missing_root/manifest.json" >/dev/null
assert_failure_contains "must supply EXPOSE_SIGNATURE_REVEALED: 2" \
  run_validator "$expose_missing_root"

# Retired behavior-change Expose key fails clearly even alongside the reveal-based key.
expose_retired_root="$fixtures_dir/expose-retired"
setup_valid_deployment "$expose_retired_root"
cat > "$expose_retired_root/config-repo/game-service.yml" <<'EOF'
game:
  rules:
    scoring:
      score-deltas:
        EXPOSE_SIGNATURE_REVEALED: 2
        EXPOSE_CHANGED_PLAYER_BEHAVIOR: 2
EOF
SIMULATION_SERVICES_DIR="$expose_retired_root/services" \
  SIMULATION_CONFIG_REPO="$expose_retired_root/config-repo" \
  SIMULATION_CATALOG="$expose_retired_root/catalog.yml" \
  SIMULATION_LANE="lane-a" \
  SIMULATION_EXPERIMENT="exp-1" \
  SIMULATION_CASE="case-1" \
  JWT_ISSUER_URI="https://issuer.example" \
  bash "$writer" "$expose_retired_root/manifest.json" >/dev/null
assert_failure_contains "must not contain the retired EXPOSE_CHANGED_PLAYER_BEHAVIOR" \
  run_validator "$expose_retired_root"

# A broker-grant script without the lane observer fails clearly.
no_observer_root="$fixtures_dir/no-observer"
setup_valid_deployment "$no_observer_root"
grep -v 'lane-observer' "$repo_root/scripts/provision-kafka-simulation-acls.sh" > "$no_observer_root/acls.sh"
SIMULATION_ACLS_SCRIPT="$no_observer_root/acls.sh" assert_failure_contains "must grant the lane observer" \
  run_validator "$no_observer_root"

# A broker-grant script reaching bot identities fails clearly.
bot_grant_root="$fixtures_dir/bot-grant"
setup_valid_deployment "$bot_grant_root"
cp "$repo_root/scripts/provision-kafka-simulation-acls.sh" "$bot_grant_root/acls.sh"
echo 'grant lane-bot --operation Read --operation Describe --topic game.events' >> "$bot_grant_root/acls.sh"
SIMULATION_ACLS_SCRIPT="$bot_grant_root/acls.sh" assert_failure_contains "must not grant broker access to bot or operator identities" \
  run_validator "$bot_grant_root"

# A broker-grant script with a wildcard grant fails clearly.
wildcard_root="$fixtures_dir/wildcard"
setup_valid_deployment "$wildcard_root"
cp "$repo_root/scripts/provision-kafka-simulation-acls.sh" "$wildcard_root/acls.sh"
echo 'grant "$lane-observer" --operation Read --topic "*"' >> "$wildcard_root/acls.sh"
SIMULATION_ACLS_SCRIPT="$wildcard_root/acls.sh" assert_failure_contains "must not contain wildcard" \
  run_validator "$wildcard_root"

# Missing manifest fails clearly.
missing_root="$fixtures_dir/missing"
setup_valid_deployment "$missing_root"
rm "$missing_root/manifest.json"
assert_failure_contains "run scripts/write-simulation-manifest.sh" \
  run_validator "$missing_root"

echo "Simulation lane deployment validator fixtures passed."
