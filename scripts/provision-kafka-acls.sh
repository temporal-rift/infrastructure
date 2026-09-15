#!/bin/bash
# Grants each service identity exactly the topic and consumer-group ACLs its own code actually
# uses (verified against the real @KafkaListener annotations and producers in game-service,
# timeline-service, and read-service — not the aspirational tables in event-schema.md). No
# principal receives a wildcard grant, and no principal receives an operation it doesn't perform:
#
#   game-service      produce game.events, game.dlq   consume timeline.events, game.commands
#   timeline-service   produce timeline.events         consume game.events
#   read-service       (no producer anywhere in its code)  consume game.events, timeline.events
#
# Consumer group ids are namespaced per service (game-service.*, timeline-service.*,
# read-service.*), so one PREFIXED group grant per service covers every consumer group that
# service uses without naming each one individually.
set -e

bootstrap_server="$1"
command_config="$2"

grant() {
  /opt/kafka/bin/kafka-acls.sh --bootstrap-server "$bootstrap_server" --command-config "$command_config" \
    --add --allow-principal "User:$1" "${@:2}"
}

grant game-service --operation Write --operation Describe --topic game.events
grant game-service --operation Write --operation Describe --topic game.dlq
grant game-service --operation Read --operation Describe --topic timeline.events
grant game-service --operation Read --operation Describe --topic game.commands
grant game-service --operation Read --group game-service. --resource-pattern-type prefixed

grant timeline-service --operation Write --operation Describe --topic timeline.events
grant timeline-service --operation Read --operation Describe --topic game.events
grant timeline-service --operation Read --group timeline-service. --resource-pattern-type prefixed

grant read-service --operation Read --operation Describe --topic game.events
grant read-service --operation Read --operation Describe --topic timeline.events
grant read-service --operation Read --group read-service. --resource-pattern-type prefixed
