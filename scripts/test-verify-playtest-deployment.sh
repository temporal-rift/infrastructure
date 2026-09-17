#!/usr/bin/env bash
# Fixture tests for verify-playtest-deployment.sh: one passing deployment plus one
# failing deployment per acceptance scenario. Runs without Docker, services, or an
# OIDC issuer. Mirrors the style of test-verify-kafka-topology.sh.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
validator="$script_dir/verify-playtest-deployment.sh"
writer="$script_dir/write-playtest-manifest.sh"

fixtures_dir="$(mktemp -d)"
trap 'rm -rf "$fixtures_dir"' EXIT

write_pom() {
  local dir="$1"
  local extra="${2:-}"
  mkdir -p "$dir"
  cat > "$dir/pom.xml" <<EOF
<project>
  <properties>
    <session-event.version>1.2.1</session-event.version>
    <action-event.version>2.2.0</action-event.version>
    <timeline-event.version>3.0.0</timeline-event.version>
    <scoring-event.version>1.0.2</scoring-event.version>
    <session-api.version>2.0.1</session-api.version>
    <action-api.version>3.0.3</action-api.version>
    <scoring-api.version>2.0.1</scoring-api.version>
    <projection-api.version>2.10.0</projection-api.version>
    <chains-api.version>1.0.0</chains-api.version>
  </properties>
$extra
</project>
EOF
}

# A complete, internally consistent fixture deployment. The compose overlay and
# gateway config under test are the real repository artifacts, not copies.
setup_valid_deployment() {
  local root="$1"
  write_pom "$root/services/game-service"
  write_pom "$root/services/timeline-service"
  write_pom "$root/services/read-service"
  mkdir -p "$root/config-repo" "$root/dist"
  echo "game: {}" > "$root/config-repo/application.yml"
  echo "game: {}" > "$root/config-repo/game-service.yml"
  echo "game: {}" > "$root/config-repo/timeline-service.yml"
  echo "game: {}" > "$root/config-repo/read-service.yml"
  echo "events: []" > "$root/catalog.yml"
  echo "<html></html>" > "$root/dist/index.html"
  echo "cert" > "$root/cert.pem"
  echo "key" > "$root/key.pem"
  PLAYTEST_SERVICES_DIR="$root/services" \
    PLAYTEST_CONFIG_REPO="$root/config-repo" \
    PLAYTEST_CATALOG="$root/catalog.yml" \
    PLAYTEST_CLIENT_DIST="$root/dist" \
    PLAYTEST_TIMING_PRESET="normal" \
    JWT_ISSUER_URI="https://issuer.example" \
    bash "$writer" "$root/manifest.json" >/dev/null
}

run_validator() {
  local root="$1"
  shift
  local compose_file="${PLAYTEST_COMPOSE_FILE:-$repo_root/compose.playtest.yml}"
  local nginx_conf="${PLAYTEST_NGINX_CONF:-$repo_root/playtest/nginx.conf}"
  local manifest_file="${PLAYTEST_MANIFEST:-$root/manifest.json}"
  local jwt="${FIX_JWT-https://issuer.example}"
  local origin="${FIX_ORIGIN-https://play.example}"
  local timing="${FIX_TIMING-normal}"
  env \
    PLAYTEST_COMPOSE_FILE="$compose_file" \
    PLAYTEST_NGINX_CONF="$nginx_conf" \
    PLAYTEST_CLIENT_DIST="$root/dist" \
    PLAYTEST_MANIFEST="$manifest_file" \
    PLAYTEST_SERVICES_DIR="$root/services" \
    PLAYTEST_CONFIG_REPO="$root/config-repo" \
    PLAYTEST_CATALOG="$root/catalog.yml" \
    PLAYTEST_TIMING_PRESET="$timing" \
    PLAYTEST_SKIP_ISSUER_REACHABILITY="${PLAYTEST_SKIP_ISSUER_REACHABILITY:-1}" \
    JWT_ISSUER_URI="$jwt" \
    PLAYTEST_EXTERNAL_ORIGIN="$origin" \
    PLAYTEST_TLS_CERT="$root/cert.pem" \
    PLAYTEST_TLS_KEY="$root/key.pem" \
    "$@" \
    bash "$validator"
}

assert_failure_contains() {
  local expected="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    echo "Expected playtest validation to fail" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected"* ]]; then
    echo "Expected failure to contain: $expected" >&2
    echo "Actual failure: $output" >&2
    exit 1
  fi
}

# Passing deployment against the real overlay and gateway config.
valid_root="$fixtures_dir/valid"
setup_valid_deployment "$valid_root"
run_validator "$valid_root"
echo "Valid deployment passes."

# Missing issuer fails clearly.
FIX_JWT= assert_failure_contains "missing JWT_ISSUER_URI" \
  run_validator "$valid_root"

# Plain-http player origin fails clearly.
FIX_ORIGIN="http://play.example" assert_failure_contains "must be https" \
  run_validator "$valid_root"

# Missing TLS key fails clearly.
assert_failure_contains "PLAYTEST_TLS_KEY file not found" \
  run_validator "$valid_root" PLAYTEST_TLS_KEY="$valid_root/absent.pem"

# Unreachable issuer discovery fails clearly.
PLAYTEST_SKIP_ISSUER_REACHABILITY=0 FIX_JWT="http://127.0.0.1:9/unreachable" \
  assert_failure_contains "not reachable" \
  run_validator "$valid_root"

# Non-single read-service replicas fail clearly.
replica_root="$fixtures_dir/replica"
setup_valid_deployment "$replica_root"
sed 's#replicas: 1#replicas: 2#' \
  "$repo_root/compose.playtest.yml" > "$replica_root/compose.yml"
PLAYTEST_COMPOSE_FILE="$replica_root/compose.yml" assert_failure_contains "single replica" \
  run_validator "$replica_root"

# Incompatible adopted contract set fails clearly.
incompatible_root="$fixtures_dir/incompatible"
setup_valid_deployment "$incompatible_root"
sed -i 's#<timeline-event.version>3.0.0</timeline-event.version>#<timeline-event.version>2.2.0</timeline-event.version>#' \
  "$incompatible_root/services/read-service/pom.xml"
assert_failure_contains "incompatible timeline-event.version pins" \
  run_validator "$incompatible_root"

# Stale manifest fails clearly.
stale_root="$fixtures_dir/stale"
setup_valid_deployment "$stale_root"
sed -i 's#<action-api.version>3.0.3</action-api.version>#<action-api.version>3.0.4</action-api.version>#' \
  "$stale_root/services/game-service/pom.xml"
assert_failure_contains "does not match the deployed POM pin" \
  run_validator "$stale_root"

# Manifest generated for a different issuer fails clearly.
issuer_root="$fixtures_dir/issuer"
setup_valid_deployment "$issuer_root"
PLAYTEST_SERVICES_DIR="$issuer_root/services" \
  PLAYTEST_CONFIG_REPO="$issuer_root/config-repo" \
  PLAYTEST_CATALOG="$issuer_root/catalog.yml" \
  PLAYTEST_CLIENT_DIST="$issuer_root/dist" \
  PLAYTEST_TIMING_PRESET="normal" \
  JWT_ISSUER_URI="https://other-issuer.example" \
  bash "$writer" "$issuer_root/manifest.json" >/dev/null
assert_failure_contains "does not match JWT_ISSUER_URI" \
  run_validator "$issuer_root"

# Rules/content drift after manifest generation fails clearly.
drift_root="$fixtures_dir/drift"
setup_valid_deployment "$drift_root"
echo "game: {drifted: true}" >> "$drift_root/config-repo/game-service.yml"
assert_failure_contains "digest does not match" \
  run_validator "$drift_root"

# Silent test overrides fail clearly.
override_root="$fixtures_dir/override"
setup_valid_deployment "$override_root"
{
  echo ""
  echo "  game-service:"
  echo '    environment:'
  echo '      SPRING_APPLICATION_JSON: {"game":{"rules":{"hand-deal-forced-types":["SCAN"],"max-eras":2}}}'
} >> "$override_root/overlay.yml"
cp "$repo_root/compose.playtest.yml" "$override_root/base-overlay.yml"
cat "$repo_root/compose.playtest.yml" "$override_root/overlay.yml" > "$override_root/compose.yml"
PLAYTEST_COMPOSE_FILE="$override_root/compose.yml" assert_failure_contains "instead of silently using it as normal rules" \
  run_validator "$override_root"

# The same override passes once labeled as a test override.
PLAYTEST_SERVICES_DIR="$override_root/services" \
  PLAYTEST_CONFIG_REPO="$override_root/config-repo" \
  PLAYTEST_CATALOG="$override_root/catalog.yml" \
  PLAYTEST_CLIENT_DIST="$override_root/dist" \
  PLAYTEST_TIMING_PRESET="test-override" \
  JWT_ISSUER_URI="https://issuer.example" \
  bash "$writer" "$override_root/manifest.json" >/dev/null
PLAYTEST_COMPOSE_FILE="$override_root/compose.yml" \
  FIX_TIMING="test-override" \
  run_validator "$override_root" PLAYTEST_MANIFEST="$override_root/manifest.json"
echo "Labeled test override passes."

# Diagnostic exposure through the player entry point fails clearly.
diagnostic_root="$fixtures_dir/diagnostic"
setup_valid_deployment "$diagnostic_root"
sed 's#proxy_pass http://playtest-game;#proxy_pass http://playtest-game;\n    proxy_pass http://grafana:3000;#' \
  "$repo_root/playtest/nginx.conf" > "$diagnostic_root/nginx.conf"
PLAYTEST_NGINX_CONF="$diagnostic_root/nginx.conf" assert_failure_contains "must not proxy diagnostic" \
  run_validator "$diagnostic_root"

# A generic ^~ /api/ route would shadow the service-specific regex routes.
shadow_root="$fixtures_dir/shadow"
setup_valid_deployment "$shadow_root"
sed 's#location /api/ {#location ^~ /api/ {#' \
  "$repo_root/playtest/nginx.conf" > "$shadow_root/nginx.conf"
PLAYTEST_NGINX_CONF="$shadow_root/nginx.conf" assert_failure_contains "must not use ^~ on the generic" \
  run_validator "$shadow_root"

# Missing manifest fails clearly.
missing_root="$fixtures_dir/missing"
setup_valid_deployment "$missing_root"
rm "$missing_root/manifest.json"
assert_failure_contains "run scripts/write-playtest-manifest.sh" \
  run_validator "$missing_root"

echo "Playtest deployment validator fixtures passed."
