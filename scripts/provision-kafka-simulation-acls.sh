#!/bin/bash
# Grants the broker authorizations a simulation lane needs, on top of the service grants in
# provision-kafka-acls.sh. Each lane runs its own broker inside its own task-owned Compose
# project, so lane topics are isolated by construction; this script adds the one grant that is
# lane-specific: the lane observer's read-only access to that lane's event topics.
#
# Roles and their broker access:
#   game-service, timeline-service, read-service  Same topic and group grants as the
#                                                 non-local demonstration (see
#                                                 provision-kafka-acls.sh); unchanged per lane.
#   lane observer  Read and Describe on game.events and timeline.events only, with a
#                  lane-owned consumer group. It never produces and never reads commands
#                  or dead-letter topics.
#   bots, operator  No broker grants at all: bots and the operator use only HTTP participant
#                   and control APIs. The deny-by-default authorizer rejects any direct
#                   broker attempt by those identities.
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
