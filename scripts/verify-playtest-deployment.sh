#!/usr/bin/env bash
# Validates the isolated playtest deployment inputs before any container starts.
# Every failure names the missing or incompatible input; nothing is defaulted
# silently into looking like a normal-rules deployment.
#
# Usage: bash scripts/verify-playtest-deployment.sh
#
# Required environment:
#   JWT_ISSUER_URI            Absolute http(s) URL of the reachable OIDC issuer.
#   PLAYTEST_EXTERNAL_ORIGIN  Absolute https URL of the player entry point.
#   PLAYTEST_TLS_CERT         Path to the TLS certificate file served by the edge.
#   PLAYTEST_TLS_KEY          Path to the TLS private key file served by the edge.
#
# Optional environment:
#   PLAYTEST_TIMING_PRESET            "normal" (default) or "test-override".
#   PLAYTEST_SKIP_ISSUER_REACHABILITY=1 skips the live issuer reachability probe.
#   PLAYTEST_COMPOSE_FILE             Override for compose.playtest.yml.
#   PLAYTEST_NGINX_CONF               Override for playtest/nginx.conf.
#   PLAYTEST_CLIENT_DIST              Override for the built static client directory.
#   PLAYTEST_CONFIG_REPO              Override for the served config-repo directory.
#   PLAYTEST_CATALOG                  Override for the served event-catalog file.
#   PLAYTEST_MANIFEST                 Override for playtest/manifest.json.
#   PLAYTEST_SERVICES_DIR             Directory holding the game-service,
#                                     timeline-service and read-service checkouts.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
workspace_default="$(cd "$repo_root/.." && pwd)"

compose_file="${PLAYTEST_COMPOSE_FILE:-$repo_root/compose.playtest.yml}"
nginx_conf="${PLAYTEST_NGINX_CONF:-$repo_root/playtest/nginx.conf}"
client_dist="${PLAYTEST_CLIENT_DIST:-$workspace_default/game-client/dist}"
manifest="${PLAYTEST_MANIFEST:-$repo_root/playtest/manifest.json}"
services_dir="${PLAYTEST_SERVICES_DIR:-$workspace_default}"
timing_preset="${PLAYTEST_TIMING_PRESET:-normal}"

fail() {
  echo "Playtest deployment invalid: $1" >&2
  exit 1
}

is_absolute_http_url() {
  [[ "$1" =~ ^https?://[^[:space:]]+$ ]]
}

# --- Identity and routing inputs ---

[[ -n "${JWT_ISSUER_URI:-}" ]] \
  || fail "missing JWT_ISSUER_URI: set it to the reachable OIDC issuer URL."
is_absolute_http_url "$JWT_ISSUER_URI" \
  || fail "JWT_ISSUER_URI must be an absolute http(s) URL, got '$JWT_ISSUER_URI'."

[[ -n "${PLAYTEST_EXTERNAL_ORIGIN:-}" ]] \
  || fail "missing PLAYTEST_EXTERNAL_ORIGIN: set it to the https player entry point URL."
is_absolute_http_url "$PLAYTEST_EXTERNAL_ORIGIN" \
  || fail "PLAYTEST_EXTERNAL_ORIGIN must be an absolute http(s) URL, got '$PLAYTEST_EXTERNAL_ORIGIN'."
[[ "$PLAYTEST_EXTERNAL_ORIGIN" =~ ^https:// ]] \
  || fail "PLAYTEST_EXTERNAL_ORIGIN must be https, got '$PLAYTEST_EXTERNAL_ORIGIN'."

[[ -n "${PLAYTEST_TLS_CERT:-}" ]] \
  || fail "missing PLAYTEST_TLS_CERT: set it to the playtest TLS certificate file."
[[ -f "$PLAYTEST_TLS_CERT" ]] \
  || fail "PLAYTEST_TLS_CERT file not found: $PLAYTEST_TLS_CERT."
[[ -n "${PLAYTEST_TLS_KEY:-}" ]] \
  || fail "missing PLAYTEST_TLS_KEY: set it to the playtest TLS key file."
[[ -f "$PLAYTEST_TLS_KEY" ]] \
  || fail "PLAYTEST_TLS_KEY file not found: $PLAYTEST_TLS_KEY."

if [[ "${PLAYTEST_SKIP_ISSUER_REACHABILITY:-}" != "1" ]]; then
  discovery="$JWT_ISSUER_URI/.well-known/openid-configuration"
  issuer_probe="$(mktemp)"
  if ! curl --fail --silent --show-error --max-time 10 "$discovery" -o "$issuer_probe"; then
    rm -f "$issuer_probe"
    fail "JWT_ISSUER_URI is not reachable: discovery document unavailable at $discovery."
  fi
  rm -f "$issuer_probe"
fi

# --- Compose overlay ---

[[ -f "$compose_file" ]] || fail "compose overlay not found: $compose_file."
grep -q "temporal-rift-playtest" "$compose_file" \
  || fail "$compose_file does not scope the task-owned project 'temporal-rift-playtest'."

# Prints the two-space-indented Compose block for one service (from its
# "  <service>:" header to the next top-level key).
service_block() {
  awk -v svc="$1" '
    /^  [A-Za-z0-9_-]+:/ { in_block = ($0 == "  " svc ":") }
    in_block { print }
  ' "$2"
}

for service in game-service timeline-service read-service; do
  service_block "$service" "$compose_file" | grep -q "ports: !override \[\]" \
    || fail "$compose_file must unpublish the direct $service host ports (ports: !override []) so players use only the edge entry point."
done

service_block "read-service" "$compose_file" | grep -qE "replicas:[[:space:]]*1([[:space:]]*(#.*)?)?$" \
  || fail "$compose_file must pin read-service to a single replica: the initial topology supports authenticated polling without multi-instance socket fan-out."

grep -q 'JWT_ISSUER_URI:?' "$compose_file" \
  || fail "$compose_file must require JWT_ISSUER_URI instead of falling back to a localhost default."
grep -q 'JWT_ISSUER_URI:-' "$compose_file" \
  && fail "$compose_file must not define a default JWT_ISSUER_URI fallback: a missing issuer must fail, not silently use localhost." \
  || true

if grep -q "SPRING_APPLICATION_JSON" "$compose_file"; then
  if grep -q -E "hand-deal-forced-types|max-eras|timer-seconds" "$compose_file"; then
    [[ "$timing_preset" == "test-override" ]] \
      || fail "$compose_file carries timing/content overrides but PLAYTEST_TIMING_PRESET is '$timing_preset': label accelerated or forced timing with PLAYTEST_TIMING_PRESET=test-override instead of silently using it as normal rules."
  fi
fi

# --- Edge gateway routing ---

[[ -f "$nginx_conf" ]] || fail "edge gateway config not found: $nginx_conf."
grep -q "listen 443 ssl" "$nginx_conf" \
  || fail "$nginx_conf must terminate HTTPS (listen 443 ssl) for same-origin API routing."
grep -q "ssl_certificate /etc/nginx/tls/cert.pem" "$nginx_conf" \
  || fail "$nginx_conf must serve the mounted playtest TLS certificate."
grep -q "listen 80" "$nginx_conf" && grep -q "return 301 https://" "$nginx_conf" \
  || fail "$nginx_conf must redirect plain HTTP to HTTPS."
grep -q "server game-service:8080" "$nginx_conf" \
  || fail "$nginx_conf must route gameplay APIs to game-service."
grep -q "server read-service:8080" "$nginx_conf" \
  || fail "$nginx_conf must route player state reads to read-service."
grep -q "server timeline-service:8080" "$nginx_conf" \
  || fail "$nginx_conf must route chain reads to timeline-service."

if grep -q -E 'location +\^~ +/api/' "$nginx_conf"; then
  fail "$nginx_conf must not use ^~ on the generic /api/ route: it would shadow the read-service and timeline-service regex routes."
fi
state_line="$(grep -n "games/.*state" "$nginx_conf" | head -n1 | cut -d: -f1 || true)"
generic_line="$(grep -n -E 'location +(\^~ +)?/api/' "$nginx_conf" | head -n1 | cut -d: -f1 || true)"
[[ -n "$state_line" && -n "$generic_line" && "$state_line" -lt "$generic_line" ]] \
  || fail "$nginx_conf must route player state reads to read-service ahead of the generic /api/ gameplay route."

proxy_targets="$(grep "proxy_pass" "$nginx_conf" || true)"
if echo "$proxy_targets" | grep -q -E "kafka-ui|grafana|zipkin|config-server|victoriametrics|victorialogs|alertmanager|vmalert|kafka-exporter"; then
  fail "$nginx_conf must not proxy diagnostic or privileged interfaces through the player entry point."
fi

# The client probes this one path before rendering sign-in. It must check the
# gameplay backend, but serve only the fixed local response, never the upstream
# actuator payload. The internal subrequest is unreachable from a browser.
readiness_block="$(sed -n '\#location = /actuator/health {#,/^[[:space:]]*}/p' "$nginx_conf")"
internal_health_block="$(sed -n '\#location = /_gameplay_health {#,/^[[:space:]]*}/p' "$nginx_conf")"
echo "$readiness_block" | grep -q 'auth_request /_gameplay_health;' \
  || fail "$nginx_conf must gate the exact client readiness path on backend health."
echo "$readiness_block" | grep -q 'try_files /readiness.json =503;' \
  || fail "$nginx_conf must serve only the fixed client readiness response."
if echo "$readiness_block" | grep -q 'proxy_pass'; then
  fail "$nginx_conf must not return the upstream actuator payload to players."
fi
echo "$internal_health_block" | grep -q 'internal;' \
  || fail "$nginx_conf must keep the backend health subrequest internal."
echo "$internal_health_block" | grep -q 'proxy_pass http://playtest-game/actuator/health;' \
  || fail "$nginx_conf must check game-service health before reporting readiness."
[[ -f "$repo_root/playtest/readiness.json" ]] \
  || fail "fixed client readiness response is missing."
grep -q './playtest/readiness.json:/etc/nginx/readiness.json:ro' "$compose_file" \
  || fail "$compose_file must mount the fixed client readiness response."

# --- Static client ---

[[ -f "$client_dist/index.html" ]] \
  || fail "built static client not found at $client_dist/index.html: build the browser client before deploying."

# --- Contract compatibility across the deployed service set ---

pom_version() {
  grep -m1 -o "<$2>[^<]*</$2>" "$1" 2>/dev/null | sed -e "s#<$2>##" -e "s#</$2>##" || true
}

game_pom="$services_dir/game-service/pom.xml"
timeline_pom="$services_dir/timeline-service/pom.xml"
read_pom="$services_dir/read-service/pom.xml"
for pom in "$game_pom" "$timeline_pom" "$read_pom"; do
  [[ -f "$pom" ]] || fail "service checkout not found at $pom: the playtest stack builds sibling services from source."
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
      fail "incompatible $tag pins across the deployed service set ($expected vs $value in $pom): deploy one compatible contract set."
    fi
  done
}

check_shared_pin "timeline-event.version" "$game_pom" "$timeline_pom" "$read_pom"
check_shared_pin "session-event.version" "$game_pom" "$timeline_pom" "$read_pom"
check_shared_pin "action-event.version" "$game_pom" "$timeline_pom" "$read_pom"
check_shared_pin "scoring-event.version" "$game_pom" "$read_pom"

# --- Manifest freshness ---

[[ -f "$manifest" ]] || fail "effective manifest not found at $manifest: run scripts/write-playtest-manifest.sh for this deployment."

manifest_value() {
  grep -m1 -o "\"$1\": \"[^\"]*\"" "$manifest" | sed -e "s#\"$1\": \"##" -e 's#"##' || true
}

[[ "$(manifest_value "timingPreset")" == "$timing_preset" ]] \
  || fail "manifest timingPreset '$(manifest_value "timingPreset")' does not match PLAYTEST_TIMING_PRESET='$timing_preset': regenerate the manifest for this deployment."
[[ "$(manifest_value "issuer")" == "$JWT_ISSUER_URI" ]] \
  || fail "manifest issuer '$(manifest_value "issuer")' does not match JWT_ISSUER_URI='$JWT_ISSUER_URI': regenerate the manifest for this deployment."

# The recorded rules/content digests are re-derived from the same inputs the
# writer uses, so a config, catalog, or client change after manifest generation
# fails here instead of deploying under a stale attribution.
config_repo="${PLAYTEST_CONFIG_REPO:-$repo_root/config-server/config-repo}"
catalog_file="${PLAYTEST_CATALOG:-$services_dir/game-service/src/main/resources/future-events.yml}"

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
    || fail "manifest $key digest does not match $path: regenerate the manifest for this deployment."
}

check_digest_matches "application.yml" "$config_repo/application.yml"
check_digest_matches "game-service.yml" "$config_repo/game-service.yml"
check_digest_matches "timeline-service.yml" "$config_repo/timeline-service.yml"
check_digest_matches "read-service.yml" "$config_repo/read-service.yml"
check_digest_matches "future-events.yml" "$catalog_file"
check_digest_matches "clientDistDigest" "$client_dist/index.html"
for pin in session-event action-event timeline-event scoring-event session-api action-api scoring-api projection-api chains-api; do
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
  esac
  # The shared event pins were already proven equal across services above, so the
  # game-service POM is representative for them; projection-api and chains-api
  # pins live only in their owning service's POM.
  [[ -n "$expected" ]] || fail "unresolvable contract pin $pin.version in the service POMs."
  [[ "$(manifest_value "$pin")" == "$expected" ]] \
    || fail "manifest $pin '$(manifest_value "$pin")' does not match the deployed POM pin '$expected': regenerate the manifest for this deployment."
done

echo "Playtest deployment inputs valid (timingPreset=$timing_preset)."
