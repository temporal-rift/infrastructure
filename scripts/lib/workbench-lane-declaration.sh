#!/usr/bin/env bash
# Derives the provenance a workbench lane declares from the lane's recorded manifest and its deployed images.
# Sourced, not executed. The formats are the ones the workbench validates when it starts.

# Prints the value of a top-level string key of a lane manifest, or nothing.
workbench_manifest_value() {
  sed -n "s/^  \"$2\": \"\\([^\"]*\\)\",\\{0,1\\}\$/\\1/p" "$1" | head -n1
}

# Prints the recorded source revision of a service, or nothing.
workbench_manifest_revision() {
  sed -n "s/^    \"$2\": \"\\([^\"]*\\)\",\\{0,1\\}\$/\\1/p" "$1" | head -n1
}

# Prints "module version" lines of the participant and event contract modules a lane manifest records. The
# simulation control contract is the operator's, not a participant or event module.
workbench_manifest_contracts() {
  awk '/^  "contracts": \{/ {capture = 1; next} capture && /^  \}/ {exit} capture {print}' "$1" \
    | sed -n 's/^    "\([^"]*\)": "\([^"]*\)",\{0,1\}$/\1 \2/p' \
    | { grep -v '^simulation-control-api ' || true; }
}

# Prints a lane's declaration as the workbench lane configuration's keys at the indentation of a lane entry's
# fields. Fails, naming the lane and the key, when a value is unavailable or malformed.
# Usage: workbench_lane_declaration <lane> <manifest> <game image digest> <timeline image digest> <read image digest>
workbench_lane_declaration() {
  local lane="$1" manifest="$2" game_image="$3" timeline_image="$4" read_image="$5"
  local rules content module version service image revision

  invalid() {
    echo "lane $lane: $1 is unavailable or malformed${2:+ ('$2')}." >&2
    return 1
  }

  rules="$(workbench_manifest_value "$manifest" rulesArtifactDigest)"
  [[ "$rules" =~ ^[0-9a-f]{64}$ ]] || { invalid rules-artifact-digest "$rules"; return 1; }
  content="$(workbench_manifest_value "$manifest" contentArtifactDigest)"
  [[ "$content" =~ ^[0-9a-f]{64}$ ]] || { invalid content-artifact-digest "$content"; return 1; }

  printf '        rules-artifact-digest: "%s"\n' "$rules"
  printf '        content-artifact-digest: "%s"\n' "$content"
  printf '        services:\n'
  for service in game-service timeline-service read-service; do
    case "$service" in
      game-service) image="$game_image" ;;
      timeline-service) image="$timeline_image" ;;
      read-service) image="$read_image" ;;
    esac
    [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]] || { invalid "services.$service.image-digest" "$image"; return 1; }
    revision="$(workbench_manifest_revision "$manifest" "$service")"
    [[ "$revision" =~ ^[0-9a-f]{7,64}$ ]] || { invalid "services.$service.source-revision" "$revision"; return 1; }
    printf '          %s:\n            image-digest: "%s"\n            source-revision: "%s"\n' \
      "$service" "$image" "$revision"
  done

  local contracts
  contracts="$(workbench_manifest_contracts "$manifest")"
  [[ -n "$contracts" ]] || { invalid contracts; return 1; }
  printf '        contracts:\n'
  while read -r module version; do
    [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || { invalid "contracts.$module" "$version"; return 1; }
    printf '          %s: "%s"\n' "$module" "$version"
  done <<< "$contracts"
}
