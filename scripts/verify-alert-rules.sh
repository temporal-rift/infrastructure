#!/usr/bin/env bash
set -euo pipefail

# Proves observability/vmalert/rules.yml's two alert rules fire exactly under their documented
# conditions, against synthetic fixtures (observability/vmalert/rules-test.yml) -- no live Kafka
# broker or running vmalert instance required. Pin matches the vmalert image in compose.yml.
image="victoriametrics/vmalert-tool:v1.152.0"
rules_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../observability/vmalert" && pwd)"

# No-op outside Git Bash on Windows; prevents MSYS from mangling the container-side /rules path in -v.
export MSYS_NO_PATHCONV=1

docker run --rm -v "${rules_dir}:/rules" "$image" unittest --files=/rules/rules-test.yml
