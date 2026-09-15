#!/bin/sh
# Mirrors capture-e2e-diagnostics.sh's reasoning for the security-e2e profile's much smaller stack:
# bound to post-integration-test, before stop-secure-system-under-test, so it runs while the
# containers still exist. Runs unconditionally; the workflow uploads the result only on failure.
set -u

target_dir="target/security-e2e-diagnostics"
mkdir -p "$target_dir"

docker compose -f compose.secure.yml logs --no-color --timestamps >"$target_dir/compose-logs.txt" 2>&1 || true
docker compose -f compose.secure.yml ps --all >"$target_dir/compose-ps.txt" 2>&1 || true
docker compose -f compose.secure.yml exec -T kafka-secure cat /opt/kafka/logs/kafka-authorizer.log \
  >"$target_dir/kafka-authorizer.log" 2>&1 || true
