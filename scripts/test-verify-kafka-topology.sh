#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
validator="$script_dir/verify-kafka-topology.sh"
fixtures_dir="$(mktemp -d)"
trap 'rm -rf "$fixtures_dir"' EXIT

write_documentation() {
  local path="$1"
  shift
  {
    printf '%s\n' '<!-- kafka-topology:start -->'
    printf '%s\n' '| Topic | Channel class | Purpose |'
    printf '%s\n' '|---|---|---|'
    for topic in "$@"; do
      printf '| `%s` | Test | Test fixture |\n' "$topic"
    done
    printf '%s\n' '<!-- kafka-topology:end -->'
  } > "$path"
}

write_provisioning() {
  local path="$1"
  shift
  {
    printf '%s\n' '# kafka-topology:start'
    printf '%s\n' 'declare -A retention_ms=('
    for topic in "$@"; do
      printf '  [%s]=1\n' "$topic"
    done
    printf '%s\n' ')'
    printf '%s\n' '# kafka-topology:end'
  } > "$path"
}

assert_failure_contains() {
  local expected="$1"
  local documentation="$2"
  local provisioning="$3"
  local output

  if output="$("$validator" "$documentation" "$provisioning" 2>&1)"; then
    echo "Expected topology validation to fail" >&2
    exit 1
  fi

  if [[ "$output" != *"$expected"* ]]; then
    echo "Expected failure to contain: $expected" >&2
    echo "Actual failure: $output" >&2
    exit 1
  fi
}

matching_documentation="$fixtures_dir/matching.md"
matching_provisioning="$fixtures_dir/matching.sh"
write_documentation "$matching_documentation" game.events game.commands
write_provisioning "$matching_provisioning" game.events game.commands
"$validator" "$matching_documentation" "$matching_provisioning"

missing_provisioning_documentation="$fixtures_dir/missing-provisioning.md"
missing_provisioning_script="$fixtures_dir/missing-provisioning.sh"
write_documentation "$missing_provisioning_documentation" game.events game.commands
write_provisioning "$missing_provisioning_script" game.events
assert_failure_contains 'Documented but not provisioned: game.commands' \
  "$missing_provisioning_documentation" "$missing_provisioning_script"

missing_documentation_file="$fixtures_dir/missing-documentation.md"
missing_documentation_script="$fixtures_dir/missing-documentation.sh"
write_documentation "$missing_documentation_file" game.events
write_provisioning "$missing_documentation_script" game.events timeline.events
assert_failure_contains 'Provisioned but not documented: timeline.events' \
  "$missing_documentation_file" "$missing_documentation_script"

echo "Kafka topology validator fixtures passed."
