#!/bin/sh
# Bound to the e2e profile's post-integration-test phase, declared before stop-system-under-test
# so it runs while the Compose stack still exists. Runs unconditionally (Maven has no
# "only on Failsafe failure" phase binding) — the workflow uploads the result only on failure.
set -u

target_dir="target/e2e-diagnostics"
mkdir -p "$target_dir"

docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml \
  logs --no-color --timestamps >"$target_dir/compose-logs.txt" 2>&1 || true

docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml \
  ps --all >"$target_dir/compose-ps.txt" 2>&1 || true

# Temporary: dump the tables that show whether/when the era-1 hand deal actually reached each
# service's own state, to diagnose the system-e2e timing race (infrastructure#21) directly from a
# CI run instead of guessing from application logs alone. Remove once that investigation closes.
docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml \
  exec -T postgres psql -U temporal_rift -d game_service -c \
  "select game_id, era_number, player_id, status, selection_origin, selection_expires_at from hand_selection order by selection_expires_at;" \
  >"$target_dir/game-service-hand-selection.txt" 2>&1 || true

docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml \
  exec -T postgres psql -U temporal_rift -d read_service -c \
  "select id, game_id, player_id, pending_hand_selection_expires_at from player_game_state;" \
  >"$target_dir/read-service-player-game-state.txt" 2>&1 || true

docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml \
  exec -T postgres psql -U temporal_rift -d read_service -c \
  "select player_game_state_id, count(*) from player_game_state_pending_hand_card group by player_game_state_id;" \
  >"$target_dir/read-service-pending-hand-cards.txt" 2>&1 || true

# Full chronological history, not just the most recent N -- ORDER BY ... DESC LIMIT 30 only ever
# shows the current front edge of processing, which looks "clustered" near query time whether or
# not there was ever an actual gap. Ascending with no limit shows the real distribution, including
# any gap between game-service's era-1 publication (~90-150s before this snapshot, depending on
# the current hand-selection-timer-seconds value) and this consumer's actual processing of it.
docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml \
  exec -T postgres psql -U temporal_rift -d read_service -c \
  "select event_id, consumer, processed_at from processed_events order by processed_at asc;" \
  >"$target_dir/read-service-processed-events.txt" 2>&1 || true

# Same reasoning for game-service's own outbox relay: confirms whether its Kafka publication (not
# just its internal hand_selection row) also happened promptly, or was itself delayed.
docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml \
  exec -T postgres psql -U temporal_rift -d game_service -c \
  "select listener_id, publication_date, completion_date from event_publication order by publication_date asc;" \
  >"$target_dir/game-service-event-publication.txt" 2>&1 || true

docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml \
  exec -T postgres psql -U temporal_rift -d read_service -c \
  "select game_id, era_number, dealt_hands from game_history_projection order by era_number;" \
  >"$target_dir/read-service-game-history.txt" 2>&1 || true

# Direct proof of consumer lag (unconsumed messages sitting on the broker) versus the message
# never having been produced at all -- LOG-END-OFFSET vs CURRENT-OFFSET per partition/group.
docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml \
  exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --all-groups --describe \
  >"$target_dir/kafka-consumer-group-lag.txt" 2>&1 || true
