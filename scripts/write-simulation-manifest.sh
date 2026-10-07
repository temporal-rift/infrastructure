#!/usr/bin/env bash
# Writes the immutable effective manifest for one isolated simulation lane: lane
# attribution, service revisions, adopted contract versions, resolved rules/content digests,
# the logical timing mode, and the identity configuration (scopes and issuer only — never
# credential material).
#
# Usage: bash scripts/write-simulation-manifest.sh [output-file]
#
# Environment:
#   SIMULATION_LANE         Lane name; scopes the Compose project and the manifest directory.
#   SIMULATION_EXPERIMENT   Experiment name recorded as lane attribution.
#   SIMULATION_CASE         Case key recorded as lane attribution.
#   JWT_ISSUER_URI          Recorded as the configured identity issuer (may be unset
#                           here; deployment validation requires it).
#
# Optional environment (fixture/testing overrides; deployments use the defaults):
#   SIMULATION_SERVICES_DIR Directory holding the service checkouts.
#   SIMULATION_CONFIG_REPO  Directory holding the served config-repo YAML files.
#   SIMULATION_CATALOG      Path to the served event-catalog file.
#
# The manifest is written to simulation/lanes/<lane>/manifest.json together with retained
# copies of every digest input, so the lane directory carries its own attribution and its
# own inspectable bundle contents. Regenerate it for every lane deployment; never hand-edit
# a generated manifest.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
workspace_root="$(cd "$repo_root/.." && pwd)"
lane="${SIMULATION_LANE:?SIMULATION_LANE must be set to the lane name.}"
experiment="${SIMULATION_EXPERIMENT:?SIMULATION_EXPERIMENT must be set to the experiment name.}"
case_key="${SIMULATION_CASE:?SIMULATION_CASE must be set to the case key.}"
output="${1:-$repo_root/simulation/lanes/$lane/manifest.json}"

# Overrides exist for the validator's fixture tests; deployments use the defaults.
services_dir="${SIMULATION_SERVICES_DIR:-$workspace_root}"
config_repo="${SIMULATION_CONFIG_REPO:-$repo_root/config-server/config-repo}"
catalog_file="${SIMULATION_CATALOG:-$services_dir/game-service/src/main/resources/future-events.yml}"

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
simulation_control_game="$(pom_version "$game_pom" "simulation-control-api.version")"
simulation_control_timeline="$(pom_version "$timeline_pom" "simulation-control-api.version")"

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
  "chains-api:$chains_api" \
  "simulation-control-api(game-service):$simulation_control_game" \
  "simulation-control-api(timeline-service):$simulation_control_timeline"; do
  if [[ "${name_value#*:}" == "" ]]; then
    echo "Cannot write manifest: unresolvable contract pin ${name_value%%:*}" >&2
    exit 1
  fi
done

if [[ "$simulation_control_game" != "$simulation_control_timeline" ]]; then
  echo "Cannot write manifest: incompatible simulation-control-api pins across the lane service set ($simulation_control_game vs $simulation_control_timeline): deploy one compatible control contract." >&2
  exit 1
fi
simulation_control="$simulation_control_game"

generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
issuer="${JWT_ISSUER_URI:-unconfigured}"

# Every digest input must exist up front: a manifest that silently records
# "missing" for a rules or catalog input would claim a pinned
# deployment it never actually saw.
for required_input in \
  "$config_repo/application.yml" \
  "$config_repo/game-service.yml" \
  "$config_repo/timeline-service.yml" \
  "$config_repo/read-service.yml" \
  "$catalog_file"; do
  if [[ ! -f "$required_input" ]]; then
    echo "Cannot write manifest: required input not found: $required_input" >&2
    exit 1
  fi
done

mkdir -p "$(dirname "$output")"
bundle_dir="$(dirname "$output")/bundle"
mkdir -p "$bundle_dir"
cp "$config_repo/application.yml" "$bundle_dir/application.yml"
cp "$config_repo/game-service.yml" "$bundle_dir/game-service.yml"
cp "$config_repo/timeline-service.yml" "$bundle_dir/timeline-service.yml"
cp "$config_repo/read-service.yml" "$bundle_dir/read-service.yml"
cp "$catalog_file" "$bundle_dir/future-events.yml"

{
  printf '{\n'
  printf '  "generatedAt": "%s",\n' "$generated_at"
  printf '  "lane": "%s",\n' "$lane"
  printf '  "experiment": "%s",\n' "$experiment"
  printf '  "case": "%s",\n' "$case_key"
  printf '  "timingMode": "LOGICAL",\n'
  printf '  "issuer": "%s",\n' "$issuer"
  printf '  "identity": {\n'
  printf '    "bots": "deterministic issuer subjects matching the configured seat/player IDs, participant APIs only",\n'
  printf '    "operatorScope": "simulation:control",\n'
  printf '    "designerScopes": ["simulation:read", "simulation:write"],\n'
  printf '    "observerScope": "simulation:observe"\n'
  printf '  },\n'
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
  printf '    "chains-api": "%s",\n' "$chains_api"
  printf '    "simulation-control-api": "%s"\n' "$simulation_control"
  printf '  },\n'
  printf '  "configDigests": {\n'
  printf '    "application.yml": "%s",\n' "$(file_digest "$config_repo/application.yml")"
  printf '    "game-service.yml": "%s",\n' "$(file_digest "$config_repo/game-service.yml")"
  printf '    "timeline-service.yml": "%s",\n' "$(file_digest "$config_repo/timeline-service.yml")"
  printf '    "read-service.yml": "%s",\n' "$(file_digest "$config_repo/read-service.yml")"
  printf '    "future-events.yml": "%s"\n' "$(file_digest "$catalog_file")"
  printf '  },\n'
  printf '  "bundle": "retained alongside this manifest under bundle/"\n'
  printf '}\n'
} > "$output"

echo "Simulation lane manifest written to $output (lane=$lane, timingMode=LOGICAL)."
