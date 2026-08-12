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
