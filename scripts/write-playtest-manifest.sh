#!/usr/bin/env bash
# Writes the immutable effective manifest for the isolated playtest deployment:
# service revisions, adopted contract versions, resolved rules/content digests,
# the active timing preset, and the static-client build digest.
#
# Usage: bash scripts/write-playtest-manifest.sh [output-file]
#
# Environment:
#   PLAYTEST_TIMING_PRESET  "normal" (default) or "test-override". Any accelerated or
#                           forced timing/content override must be recorded as
#                           "test-override" so it is identified, never silent.
#   JWT_ISSUER_URI          Recorded as the configured identity issuer (may be unset
#                           here; deployment validation requires it).
#
# Optional environment (fixture/testing overrides; deployments use the defaults):
#   PLAYTEST_SERVICES_DIR  Directory holding the service checkouts.
#   PLAYTEST_CONFIG_REPO   Directory holding the served config-repo YAML files.
#   PLAYTEST_CATALOG       Path to the served event-catalog file.
#   PLAYTEST_CLIENT_DIST   Directory holding the built static client.
#
# The manifest is written next to the gateway config (playtest/manifest.json) so the
# deployment directory carries its own attribution. Regenerate it for every deployment;
# never hand-edit a generated manifest.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
workspace_root="$(cd "$repo_root/.." && pwd)"
output="${1:-$repo_root/playtest/manifest.json}"
timing_preset="${PLAYTEST_TIMING_PRESET:-normal}"

# Overrides exist for the validator's fixture tests; deployments use the defaults.
services_dir="${PLAYTEST_SERVICES_DIR:-$workspace_root}"
config_repo="${PLAYTEST_CONFIG_REPO:-$repo_root/config-server/config-repo}"
catalog_file="${PLAYTEST_CATALOG:-$services_dir/game-service/src/main/resources/future-events.yml}"
client_dist="${PLAYTEST_CLIENT_DIST:-$workspace_root/game-client/dist}"

pom_version() {
  local pom="$1"
  local tag="$2"
  grep -m1 -o "<$tag>[^<]*</$tag>" "$pom" 2>/dev/null | sed -e "s#<$tag>##" -e "s#</$tag>##" || true
}

service_ref() {
  local dir="$1"
  if [[ -d "$dir/.git" ]] || git -C "$dir" rev-parse --git-dir >/dev/null 2>&1; then
    git -C "$dir" rev-parse HEAD 2>/dev/null || echo "unknown"
  else
    echo "unknown"
  fi
}

file_digest() {
  local path="$1"
  if [[ -f "$path" ]]; then
    sha256sum "$path" | awk '{print $1}'
  else
    echo "missing"
  fi
}

game_pom="$services_dir/game-service/pom.xml"
timeline_pom="$services_dir/timeline-service/pom.xml"
read_pom="$services_dir/read-service/pom.xml"

session_event="$(pom_version "$game_pom" "session-event.version")"
action_event="$(pom_version "$game_pom" "action-event.version")"
timeline_event="$(pom_version "$game_pom" "timeline-event.version")"
scoring_event="$(pom_version "$game_pom" "scoring-event.version")"
session_api="$(pom_version "$game_pom" "session-api.version")"
action_api="$(pom_version "$game_pom" "action-api.version")"
scoring_api="$(pom_version "$game_pom" "scoring-api.version")"
projection_api="$(pom_version "$read_pom" "projection-api.version")"
chains_api="$(pom_version "$timeline_pom" "chains-api.version")"

# A missing pin is a broken manifest, not an empty string.
for name_value in \
  "session-event:$session_event" \
  "action-event:$action_event" \
  "timeline-event:$timeline_event" \
  "scoring-event:$scoring_event" \
  "session-api:$session_api" \
  "action-api:$action_api" \
  "scoring-api:$scoring_api" \
  "projection-api:$projection_api" \
  "chains-api:$chains_api"; do
  if [[ "${name_value#*:}" == "" ]]; then
    echo "Cannot write manifest: unresolvable contract pin ${name_value%%:*}" >&2
    exit 1
  fi
done

generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
issuer="${JWT_ISSUER_URI:-unconfigured}"

mkdir -p "$(dirname "$output")"
{
  printf '{\n'
  printf '  "generatedAt": "%s",\n' "$generated_at"
  printf '  "timingPreset": "%s",\n' "$timing_preset"
  printf '  "issuer": "%s",\n' "$issuer"
  printf '  "services": {\n'
  printf '    "game-service": "%s",\n' "$(service_ref "$services_dir/game-service")"
  printf '    "timeline-service": "%s",\n' "$(service_ref "$services_dir/timeline-service")"
  printf '    "read-service": "%s"\n' "$(service_ref "$services_dir/read-service")"
  printf '  },\n'
  printf '  "contracts": {\n'
  printf '    "session-event": "%s",\n' "$session_event"
  printf '    "action-event": "%s",\n' "$action_event"
  printf '    "timeline-event": "%s",\n' "$timeline_event"
  printf '    "scoring-event": "%s",\n' "$scoring_event"
  printf '    "session-api": "%s",\n' "$session_api"
  printf '    "action-api": "%s",\n' "$action_api"
  printf '    "scoring-api": "%s",\n' "$scoring_api"
  printf '    "projection-api": "%s",\n' "$projection_api"
  printf '    "chains-api": "%s"\n' "$chains_api"
  printf '  },\n'
  printf '  "configDigests": {\n'
  printf '    "application.yml": "%s",\n' "$(file_digest "$config_repo/application.yml")"
  printf '    "game-service.yml": "%s",\n' "$(file_digest "$config_repo/game-service.yml")"
  printf '    "timeline-service.yml": "%s",\n' "$(file_digest "$config_repo/timeline-service.yml")"
  printf '    "read-service.yml": "%s",\n' "$(file_digest "$config_repo/read-service.yml")"
  printf '    "future-events.yml": "%s"\n' "$(file_digest "$catalog_file")"
  printf '  },\n'
  printf '  "clientDistDigest": "%s"\n' "$(file_digest "$client_dist/index.html")"
  printf '}\n'
} > "$output"

echo "Playtest manifest written to $output (timingPreset=$timing_preset)."
