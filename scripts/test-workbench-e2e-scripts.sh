#!/usr/bin/env bash
# Fixture tests for the workbench E2E lane scripts (recycle-simulation-lane.sh, cleanup-workbench-e2e.sh and the
# recycler's HTTP handler) against a fake `docker` that records its arguments. Needs no Docker daemon.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
calls="$work/docker-calls.txt"
: > "$calls"

# A fake docker: records every call; `compose ls` lists the projects named in $FAKE_PROJECTS.
cat > "$work/bin/docker" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_DOCKER_CALLS"
if [[ "$1 $2" == "compose ls" ]]; then
  printf '['
  first=true
  for project in $FAKE_PROJECTS; do
    $first || printf ','
    first=false
    printf '{"Name":"%s","Status":"running(3)"}' "$project"
  done
  printf ']\n'
fi
if [[ "$1 $2" == "network inspect" ]]; then
  exit "${FAKE_NETWORK_EXISTS:-0}"
fi
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

# --- recycle-simulation-lane.sh -------------------------------------------------------------------------------

cat > "$work/lane.env" <<'ENV'
SIMULATION_LANE=wbe2e-3
SIMULATION_EXPERIMENT=e
SIMULATION_CASE=c
ENV
bash "$repo_root/scripts/recycle-simulation-lane.sh" "$work/lane.env" > /dev/null

grep -q -- "-p temporal-rift-sim-wbe2e-3 .* down -v --remove-orphans" "$calls" \
  || fail "recycling must remove the lane's own project volumes and containers"
grep -q -- "-p temporal-rift-sim-wbe2e-3 .* up -d --wait .* game-service timeline-service read-service kafka-observer" "$calls" \
  || fail "recycling must start the lane again and wait for readiness"
if grep -v -- "-p temporal-rift-sim-wbe2e-3" "$calls" | grep -q .; then
  fail "recycling touched a project other than its own lane"
fi

: > "$calls"
printf 'SIMULATION_LANE=bad lane;rm\n' > "$work/bad.env"
if bash "$repo_root/scripts/recycle-simulation-lane.sh" "$work/bad.env" > /dev/null 2>&1; then
  fail "an unsafe lane name must be refused"
fi
[[ ! -s "$calls" ]] || fail "an unsafe lane name must reach the container runtime with nothing"

# --- cleanup-workbench-e2e.sh ---------------------------------------------------------------------------------

: > "$calls"
export FAKE_PROJECTS="my-dev-stack temporal-rift-e2e temporal-rift-playtest temporal-rift-sim-wbe2e-decoy temporal-rift-sim-wbe2e-1 temporal-rift-workbench-e2e temporal-rift-sim-wbe2e-2 temporal-rift-wbe2e-ordinary temporal-rift-sim-other-1"
listed="$(bash "$repo_root/scripts/cleanup-workbench-e2e.sh" --list)"

for owned in temporal-rift-sim-wbe2e-1 temporal-rift-sim-wbe2e-2 temporal-rift-wbe2e-ordinary temporal-rift-workbench-e2e network:temporal-rift-workbench-e2e-net; do
  grep -qx "$owned" <<< "$listed" || fail "cleanup must name $owned"
done
for protected in my-dev-stack temporal-rift-e2e temporal-rift-playtest temporal-rift-sim-wbe2e-decoy temporal-rift-sim-other-1; do
  if grep -qx "$protected" <<< "$listed"; then
    fail "cleanup must spare $protected"
  fi
done
[[ "$(grep -n 'temporal-rift-workbench-e2e$' <<< "$listed" | cut -d: -f1)" -gt "$(grep -n 'temporal-rift-sim-wbe2e-2' <<< "$listed" | cut -d: -f1)" ]] \
  || fail "the stack must be removed after the lanes attached to its network"
if grep -q " down " "$calls"; then
  fail "--list must change nothing"
fi

: > "$calls"
bash "$repo_root/scripts/cleanup-workbench-e2e.sh" > /dev/null
down_calls="$(grep -c " down -v --remove-orphans" "$calls")"
[[ "$down_calls" == "4" ]] || fail "cleanup must remove exactly the 4 owned projects, removed $down_calls"
grep -q "network rm temporal-rift-workbench-e2e-net" "$calls" || fail "cleanup must remove the shared network"

# --- the recycler's HTTP handler ------------------------------------------------------------------------------

mkdir -p "$work/repo/target/workbench-e2e/lanes" "$work/repo/scripts"
cp "$repo_root/scripts/recycle-simulation-lane.sh" "$work/repo/scripts/"
cp "$work/lane.env" "$work/repo/target/workbench-e2e/lanes/wbe2e-3.env"
handler="$repo_root/simulation/recycler/handler.sh"

request() {
  # $1 request line, $2 authorization header value (may be empty)
  local auth=""
  [[ -n "${2:-}" ]] && auth=$'Authorization: '"$2"$'\r\n'
  printf '%s\r\n%sContent-Length: 2\r\n\r\n{}' "$1" "$auth" \
    | LANE_RECYCLER_REPO="$work/repo" bash "$handler" 2> /dev/null
}

status_of() { head -n1 | tr -d '\r'; }

[[ "$(request 'POST /lanes/wbe2e-3/reset HTTP/1.1' 'Bearer token' | status_of)" == "HTTP/1.1 204 No Content" ]] \
  || fail "a reset of a provisioned lane answers 204"
[[ "$(request 'POST /lanes/wbe2e-3/reset HTTP/1.1' '' | status_of)" == "HTTP/1.1 401 Unauthorized" ]] \
  || fail "a reset without a bearer token answers 401"
[[ "$(request 'POST /lanes/unknown/reset HTTP/1.1' 'Bearer token' | status_of)" == "HTTP/1.1 404 Not Found" ]] \
  || fail "a reset of an unknown lane answers 404"
[[ "$(request 'GET /lanes/wbe2e-3/reset HTTP/1.1' 'Bearer token' | status_of)" == "HTTP/1.1 404 Not Found" ]] \
  || fail "only POST resets a lane"
[[ "$(request 'POST /lanes/../etc/reset HTTP/1.1' 'Bearer token' | status_of)" == "HTTP/1.1 404 Not Found" ]] \
  || fail "a path outside the lane namespace answers 404"

# --- summarize-workbench-e2e.sh ---------------------------------------------------------------------------------

if command -v jq > /dev/null; then
  mkdir -p "$work/evidence"
  cat > "$work/evidence/core.json" <<'JSON'
{"formatVersion":1,"suite":"core","lanes":[{"variant":"baseline"},{"variant":"candidate"}],"maxEras":2,"timingMode":"LOGICAL",
 "matrixCounts":{"requested":220,"succeeded":218,"failed":2,"cancelled":0},"matrixRuntimeSeconds":7200,"matrixThroughputCasesPerHour":110.0,
 "reproduction":{"state":"MATCH","seconds":90},"redelivery":{"recordsRedelivered":500,"cases":3}}
JSON
  printf '#1
g,12.5%%,1.5GiB / 15GiB
h,3%%,512MiB / 15GiB
#2
g,20%%,2GiB / 15GiB
' > "$work/samples.txt"
  summary="$(bash "$repo_root/scripts/summarize-workbench-e2e.sh" "$work/evidence" "$work/samples.txt")"
  grep -q "220 requested, 218 succeeded, 2 failed" <<< "$summary" || fail "the summary must state the matrix accounting"
  grep -q "Peak memory: 2048 MiB" <<< "$summary" || fail "the summary must state the peak memory"
  grep -q "not a performance claim" <<< "$summary" || fail "the summary must disclaim performance claims"
fi

echo "Workbench E2E scripts fixtures passed."
