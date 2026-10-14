#!/usr/bin/env bash
# Fixture tests for recycle-simulation-lane.sh against a fake `docker` that records its arguments. Needs no Docker daemon.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
calls="$work/docker-calls.txt"
: > "$calls"

cat > "$work/bin/docker" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_DOCKER_CALLS"
exit 0
FAKE
# flock may be absent where this runs; the fake just runs the locked command.
cat > "$work/bin/flock" <<'FAKE'
#!/usr/bin/env bash
shift
exec "$@"
FAKE
chmod +x "$work/bin/docker" "$work/bin/flock"
export PATH="$work/bin:$PATH"
export FAKE_DOCKER_CALLS="$calls"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

cat > "$work/lane.env" <<'ENV'
SIMULATION_LANE=lane-3
SIMULATION_EXPERIMENT=e
SIMULATION_CASE=c
ENV
bash "$repo_root/scripts/recycle-simulation-lane.sh" "$work/lane.env" > /dev/null

grep -q -- "-p temporal-rift-sim-lane-3 .* down -v --remove-orphans" "$calls" \
  || fail "recycling must remove the lane's own project volumes and containers"
grep -q -- "-p temporal-rift-sim-lane-3 .* up -d --wait .* game-service timeline-service read-service kafka-observer" "$calls" \
  || fail "recycling must start the lane again and wait for readiness"
if grep -v -- "-p temporal-rift-sim-lane-3" "$calls" | grep -q .; then
  fail "recycling touched a project other than its own lane"
fi

: > "$calls"
printf 'SIMULATION_LANE=bad lane;rm\n' > "$work/bad.env"
if bash "$repo_root/scripts/recycle-simulation-lane.sh" "$work/bad.env" > /dev/null 2>&1; then
  fail "an unsafe lane name must be refused"
fi
[[ ! -s "$calls" ]] || fail "an unsafe lane name must reach the container runtime with nothing"

echo "recycle-simulation-lane.sh checks passed"
