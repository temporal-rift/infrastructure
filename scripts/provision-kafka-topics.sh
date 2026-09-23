#!/bin/bash
# Shared by compose.yml's local dev broker and compose.secure.yml's demonstration of the
# non-local authenticated topology, so retention per topic class can never drift between the
# two. retention.ms is pinned explicitly instead of left at the broker default, matching
# the intended retention policy: domain replay window for game.events/timeline.events, short-lived for the
# transient game.commands, extended for source-specific dead-letter topics.
#
# Usage: provision-kafka-topics.sh <bootstrap-server> [command-config-file]
# The optional command-config-file is a Kafka client properties file (e.g. SASL credentials)
# used for an authenticated broker; omit it for the plaintext local dev broker.
set -e

bootstrap_server="$1"
command_config=()
if [ -n "${2:-}" ]; then
  command_config=(--command-config "$2")
fi

# kafka-topology:start
declare -A retention_ms=(
  [game.events]=604800000
  [timeline.events]=604800000
  [game.commands]=86400000
  [game.events.dlq]=2592000000
  [timeline.events.dlq]=2592000000
  [game.commands.dlq]=2592000000
)
# kafka-topology:end

for topic in "${!retention_ms[@]}"; do
  # --if-not-exists silently ignores --config on a topic that already exists, so retention is
  # reconciled separately with --alter on every run.
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap_server" "${command_config[@]}" \
    --create --if-not-exists --topic "$topic" --partitions 3 --replication-factor 1 \
    --config "retention.ms=${retention_ms[$topic]}"
  /opt/kafka/bin/kafka-configs.sh --bootstrap-server "$bootstrap_server" "${command_config[@]}" \
    --alter --entity-type topics --entity-name "$topic" \
    --add-config "retention.ms=${retention_ms[$topic]}"
done
