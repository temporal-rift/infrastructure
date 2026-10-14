#!/usr/bin/env bash
# One HTTP exchange of the lane recycler, run by socat per connection:
#   POST /lanes/{lane}/reset   Authorization: Bearer <token>   ->  204 once the lane is clean and ready
# The lane must have a provisioned env file; the bearer token must be present (the workbench sends its
# lane operator token). Concurrent resets of one lane are serialized. Any other request is refused.
set -uo pipefail

respond() {
  local status="$1" reason="$2" body="${3:-}"
  printf 'HTTP/1.1 %s %s\r\nContent-Type: application/json\r\nContent-Length: %s\r\nConnection: close\r\n\r\n%s' \
    "$status" "$reason" "${#body}" "$body"
}

IFS=' ' read -r method path _ || exit 0
auth=""
length=0
while IFS= read -r header; do
  header="${header%$'\r'}"
  [[ -z "$header" ]] && break
  case "${header,,}" in
    authorization:*) auth="${header#*: }" ;;
    content-length:*) length="${header#*: }" ;;
  esac
done
[[ "$length" =~ ^[0-9]+$ && "$length" -gt 0 ]] && head -c "$length" > /dev/null

if [[ "$method" != "POST" || ! "$path" =~ ^/lanes/([A-Za-z0-9][A-Za-z0-9-]*)/reset$ ]]; then
  respond 404 "Not Found" '{"code":"NOT_FOUND"}'
  exit 0
fi
lane="${BASH_REMATCH[1]}"
if [[ "$auth" != Bearer\ ?* ]]; then
  respond 401 "Unauthorized" '{"code":"AUTHENTICATION_REQUIRED"}'
  exit 0
fi
repo="${LANE_RECYCLER_REPO:?LANE_RECYCLER_REPO must name the repository checkout path}"
env_file="$repo/target/workbench-e2e/lanes/$lane.env"
if [[ ! -f "$env_file" ]]; then
  respond 404 "Not Found" '{"code":"UNKNOWN_LANE"}'
  exit 0
fi

echo "reset requested for lane $lane" >&2
if flock "/tmp/lane-$lane.lock" bash "$repo/scripts/recycle-simulation-lane.sh" "$env_file" >&2; then
  printf 'HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n'
else
  respond 502 "Bad Gateway" '{"code":"LANE_RECYCLE_FAILED"}'
fi
