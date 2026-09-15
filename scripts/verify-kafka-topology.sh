#!/usr/bin/env bash
set -euo pipefail

documentation_file="${1:-README.md}"
provisioning_file="${2:-scripts/provision-kafka-topics.sh}"

if [[ ! -f "$documentation_file" ]]; then
  echo "Documentation file not found: $documentation_file" >&2
  exit 1
fi

if [[ ! -f "$provisioning_file" ]]; then
  echo "Provisioning file not found: $provisioning_file" >&2
  exit 1
fi

documented_topics_file="$(mktemp)"
provisioned_topics_file="$(mktemp)"
trap 'rm -f "$documented_topics_file" "$provisioned_topics_file"' EXIT

extract_documented_topics() {
  local in_topology=false
  local saw_start=false
  local saw_end=false
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      '<!-- kafka-topology:start -->')
        if "$saw_start"; then
          echo "Documentation contains multiple kafka topology start markers" >&2
          return 1
        fi
        in_topology=true
        saw_start=true
        ;;
      '<!-- kafka-topology:end -->')
        if ! "$in_topology"; then
          echo "Documentation contains a kafka topology end marker without a start marker" >&2
          return 1
        fi
        in_topology=false
        saw_end=true
        ;;
      *)
        if "$in_topology" && [[ "$line" =~ ^\|[[:space:]]*\`([a-z0-9.-]+)\`[[:space:]]*\| ]]; then
          printf '%s\n' "${BASH_REMATCH[1]}"
        fi
        ;;
    esac
  done < "$documentation_file"

  if ! "$saw_start" || ! "$saw_end" || "$in_topology"; then
    echo "Documentation must contain one complete kafka topology marker section" >&2
    return 1
  fi
}

extract_provisioned_topics() {
  local in_topology=false
  local saw_start=false
  local saw_end=false
  local line

  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      '# kafka-topology:start')
        if "$saw_start"; then
          echo "Provisioning script contains multiple kafka topology start markers" >&2
          return 1
        fi
        in_topology=true
        saw_start=true
        ;;
      '# kafka-topology:end')
        if ! "$in_topology"; then
          echo "Provisioning script contains a kafka topology end marker without a start marker" >&2
          return 1
        fi
        in_topology=false
        saw_end=true
        ;;
      *)
        if "$in_topology" && [[ "$line" =~ ^[[:space:]]*\[([a-z0-9.-]+)\]= ]]; then
          printf '%s\n' "${BASH_REMATCH[1]}"
        fi
        ;;
    esac
  done < "$provisioning_file"

  if ! "$saw_start" || ! "$saw_end" || "$in_topology"; then
    echo "Provisioning script must contain one complete kafka topology marker section" >&2
    return 1
  fi
}

extract_documented_topics | sort -u > "$documented_topics_file"
extract_provisioned_topics | sort -u > "$provisioned_topics_file"

if [[ ! -s "$documented_topics_file" ]]; then
  echo "Documentation kafka topology section contains no topics" >&2
  exit 1
fi

if [[ ! -s "$provisioned_topics_file" ]]; then
  echo "Provisioning kafka topology section contains no topics" >&2
  exit 1
fi

has_mismatch=false
while IFS= read -r topic; do
  [[ -z "$topic" ]] && continue
  echo "Documented but not provisioned: $topic" >&2
  has_mismatch=true
done < <(comm -23 "$documented_topics_file" "$provisioned_topics_file")

while IFS= read -r topic; do
  [[ -z "$topic" ]] && continue
  echo "Provisioned but not documented: $topic" >&2
  has_mismatch=true
done < <(comm -13 "$documented_topics_file" "$provisioned_topics_file")

if "$has_mismatch"; then
  exit 1
fi

echo "Kafka topology documentation matches provisioning."
