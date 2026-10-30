#!/usr/bin/env bash
# Shared definitions of the deployed workbench E2E topology. Sourced, not executed.
#
# Everything the E2E deployment creates carries one of two name prefixes, which is also the complete
# set of things its cleanup may remove:
#   projects  temporal-rift-workbench-e2e, temporal-rift-sim-wbe2e-<n>, temporal-rift-wbe2e-ordinary
#   network   temporal-rift-workbench-e2e-net

workbench_e2e_network="temporal-rift-workbench-e2e-net"
workbench_e2e_project="temporal-rift-workbench-e2e"
workbench_e2e_ordinary_project="temporal-rift-wbe2e-ordinary"
workbench_e2e_lane_prefix="wbe2e-"
workbench_e2e_issuer_url="http://localhost:19000/default"
workbench_e2e_jwks_url="http://issuer:8080/default/jwks"
workbench_e2e_workbench_url="http://localhost:18200"

# Compose project name of a lane.
workbench_e2e_lane_project() {
  printf 'temporal-rift-sim-%s' "$1"
}

# The Compose files that make up one observed lane, relative to the repository root.
workbench_e2e_lane_files=(-f compose.yml -f compose.simulation.yml -f compose.simulation-observer.yml)

# Deterministic UUID-shaped bot player ID for a lane seat.
workbench_e2e_bot_id() {
  local h
  h="$(printf 'wbe2e-bot-%s-%s' "$1" "$2" | sha256sum | awk '{print $1}')"
  printf '%s-%s-4%s-8%s-%s' "${h:0:8}" "${h:8:4}" "${h:13:3}" "${h:17:3}" "${h:20:12}"
}

# Mints a bearer token from the E2E issuer for a subject, optionally with a scope.
# Usage: workbench_e2e_mint <subject> [scope]
workbench_e2e_mint() {
  local subject="$1" scope="${2:-}"
  local args=(--silent --show-error --fail --max-time 20 -X POST
    --data-urlencode "grant_type=client_credentials"
    --data-urlencode "client_id=$subject"
    --data-urlencode "client_secret=not-checked")
  [[ -n "$scope" ]] && args+=(--data-urlencode "scope=$scope")
  curl "${args[@]}" "$workbench_e2e_issuer_url/token" \
    | sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p'
}

# The simulation-workbench revision that closes each lane case on read-service as well as game-service and
# timeline-service. The workbench image is built from the sibling checkout, so an older checkout provisions cleanly
# and then never closes a case's context on read-service.
workbench_e2e_min_workbench_revision="f7270ae0efab4964ec0f634590244bd35e615cbd"

# Succeeds when the workbench checkout contains the minimum revision; otherwise prints why to stderr and fails.
# Usage: workbench_e2e_require_workbench_revision <checkout-dir>
workbench_e2e_require_workbench_revision() {
  local dir="$1" short="${workbench_e2e_min_workbench_revision:0:7}"
  git -C "$dir" rev-parse --git-dir > /dev/null 2>&1 \
    || { echo "$dir is not a git checkout, so its revision cannot be checked against $short." >&2; return 1; }
  git -C "$dir" cat-file -e "${workbench_e2e_min_workbench_revision}^{commit}" 2> /dev/null \
    || { echo "the simulation-workbench checkout at $dir does not contain revision $short, which closes each case on read-service; fetch it (a shallow clone may need to be deepened)." >&2; return 1; }
  git -C "$dir" merge-base --is-ancestor "$workbench_e2e_min_workbench_revision" HEAD 2> /dev/null \
    || { echo "the simulation-workbench checkout at $dir predates revision $short, which closes each case on read-service; update it." >&2; return 1; }
}
