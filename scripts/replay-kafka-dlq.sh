#!/usr/bin/env bash
set -euo pipefail

# This direct replay goal does not run the E2E profiles; those profiles surround integration tests
# with Maven 4's before:integration-test and after:integration-test lifecycle boundaries.

if [[ "$#" -eq 0 ]]; then
  echo "Usage: $0 --bootstrap-server <host:port> --source-topic <topic> --game-id <game-id> [--command-config <file>]" >&2
  exit 2
fi

mvn -q -DskipTests \
  -Dexec.mainClass=io.github.temporalrift.systemtest.replay.KafkaDlqReplay \
  -Dexec.args="$*" \
  exec:java
