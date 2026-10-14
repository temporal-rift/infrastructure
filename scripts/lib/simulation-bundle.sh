#!/usr/bin/env bash
# Shared definitions of a simulation lane's configuration bundle. Sourced, not executed.
#
# A bundle is the served config-repo directory plus the event catalog. Its effective digests are
# derived only from file bytes, so the manifest writer, the deployment verifier and the bundle
# preparation script cannot disagree about what a lane serves.

# The served rules files, in the fixed order their digests are combined.
simulation_rules_files=(application.yml game-service.yml read-service.yml timeline-service.yml)

simulation_file_digest() {
  if [[ -f "$1" ]]; then
    sha256sum "$1" | awk '{print $1}'
  else
    echo "missing"
  fi
}

# SHA-256 over "<name>:<file digest>" lines of the served rules files.
simulation_effective_rules_digest() {
  local config_repo="$1"
  local name
  for name in "${simulation_rules_files[@]}"; do
    printf '%s:%s\n' "$name" "$(simulation_file_digest "$config_repo/$name")"
  done | sha256sum | awk '{print $1}'
}

# The content digest is the event catalog's own digest.
simulation_effective_content_digest() {
  simulation_file_digest "$1"
}

# A lane's bundle files are the artifact it serves, so the artifact digests equal the effective digests.
simulation_rules_artifact_digest() {
  simulation_effective_rules_digest "$1"
}

simulation_content_artifact_digest() {
  simulation_effective_content_digest "$1"
}
