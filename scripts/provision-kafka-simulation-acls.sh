#!/bin/bash
# Defines the broker authorizations a simulation lane requires, on top of the service grants in
# provision-kafka-acls.sh. This is the lane's grant contract: it is checked by
# verify-simulation-deployment.sh and applied by running this script against a lane broker
# that enforces authorization (deny-by-default authorizer with per-identity credentials),
# after the broker is healthy and before services or the observer attach:
#
#   /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list >/dev/null 2>&1
#   bash scripts/provision-kafka-simulation-acls.sh kafka:9092 /path/to/admin.properties <lane>
#
# The delivered lane topology does not apply it at startup: each lane project starts its own
# single-tenant plaintext broker, topics, and volumes, so cross-lane broker access is
# impossible by construction and the lane network carries only lane members. Run this script
# when the lane broker is hardened with an authorizer; until then the observer boundary
# inside the lane network is the documented observer-only consumer role, not broker
# enforcement.
#
# Roles and their broker access:
#   game-service, timeline-service, read-service  Same topic and group grants as the
#                                                 non-local demonstration (see
#                                                 provision-kafka-acls.sh); unchanged per lane.
#   lane observer  Read and Describe on game.events and timeline.events only, with a
#                  lane-owned consumer group. It never produces and never reads commands
#                  or dead-letter topics.
#   bots, operator  No broker grants at all: bots and the operator use only HTTP participant
#                   and control APIs. On a broker with the authorizer enabled, any direct
#                   broker attempt by those identities is denied.
#
# Usage: provision-kafka-simulation-acls.sh <bootstrap-server> <command-config-file> [lane]
# The lane names the observer principal (<lane>-observer) and its consumer group prefix.
# No principal receives a wildcard grant.
set -e

bootstrap_server="$1"
command_config="$2"
lane="${3:-sim-lane}"

grant() {
  /opt/kafka/bin/kafka-acls.sh --bootstrap-server "$bootstrap_server" --command-config "$command_config" \
    --add --allow-principal "User:$1" "${@:2}"
}

grant "$lane-observer" --operation Read --operation Describe --topic game.events
grant "$lane-observer" --operation Read --operation Describe --topic timeline.events
grant "$lane-observer" --operation Read --group "$lane-observer." --resource-pattern-type prefixed
