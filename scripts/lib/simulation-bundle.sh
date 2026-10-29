#!/usr/bin/env bash
# Shared definitions of a simulation lane's configuration bundle. Sourced, not executed.
#
# A bundle is the served config-repo directory plus the event catalog. Its effective rules document
# is the canonical EffectiveRules document resolved from the served YAML, and its content digest is
# the event catalog's own digest. The manifest writer, the deployment verifier and the bundle
# preparation script all derive these values here so they cannot disagree.

simulation_lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# A lane requires simulation-control-api 2.1.0 or a later compatible 2.x release on every lane service: 2.1.0 is
# the first version read-service serves, so it is the first that closes a case on all three services, and a
# service holding state of a closed case refuses the next one instead of playing it.
simulation_control_api_required="2.1.0 or a later 2.x"
simulation_control_api_services="game-service timeline-service read-service"

simulation_control_api_accepted() {
  [[ "$1" =~ ^2\.([0-9]+)\.[0-9]+$ ]] && (( BASH_REMATCH[1] >= 1 ))
}

simulation_file_digest() {
  if [[ -f "$1" ]]; then
    sha256sum "$1" | awk '{print $1}'
  else
    echo "missing"
  fi
}

# Prints a Python interpreter that can import PyYAML, or fails with the required dependency.
simulation_python() {
  local candidates=(python3 python)
  # The Windows Store's python3 alias can block on an interactive prompt, while its python alias is real.
  if [[ "${OS:-}" == "Windows_NT" ]]; then
    candidates=(python py)
  fi
  local candidate
  for candidate in "${candidates[@]}"; do
    if command -v "$candidate" > /dev/null 2>&1 && "$candidate" -c 'import yaml' > /dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  echo "Cannot resolve effective rules: Python 3 with PyYAML is required (pip install pyyaml)." >&2
  return 1
}

# Writes the canonical EffectiveRules document for a config repo to the given path and prints its
# effective rules digest. Fails with RULES_CONFLICT when two services disagree about a key.
simulation_effective_rules_document() {
  local config_repo="$1"
  local output="$2"
  local python
  python="$(simulation_python)" || return 1
  "$python" "$simulation_lib_dir/effective_rules.py" resolve \
    --config-repo "$config_repo" --output "$output"
}

# Prints the effective rules digest of the document resolved from a config repo.
simulation_effective_rules_digest() {
  local config_repo="$1"
  local python
  python="$(simulation_python)" || return 1
  "$python" "$simulation_lib_dir/effective_rules.py" digest --config-repo "$config_repo"
}

# Prints the effective rules digest of an existing EffectiveRules document file.
simulation_effective_rules_document_digest() {
  local document="$1"
  local python
  python="$(simulation_python)" || return 1
  "$python" "$simulation_lib_dir/effective_rules.py" document-digest --file "$document"
}

# The content digest is the event catalog's own digest.
simulation_effective_content_digest() {
  simulation_file_digest "$1"
}

# A lane's rules artifact is the retained EffectiveRules document, so its digest is the file's own.
simulation_rules_artifact_digest() {
  simulation_file_digest "$1"
}

simulation_content_artifact_digest() {
  simulation_effective_content_digest "$1"
}
