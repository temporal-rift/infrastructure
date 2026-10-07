# Simulation lanes

An isolated simulation lane is one game-service, timeline-service, and read-service
deployment running one logical case at a time against real services with independent
PostgreSQL databases, broker topics, identities, and frozen configuration. Bounded
concurrency uses independent lanes. Durable workbench evidence lives outside the
disposable lane project.

## Prerequisites

- The `game-service`, `timeline-service`, and `read-service` checkouts as siblings of
  this repository (the layout the lane overlay's build contexts require).
- An OIDC issuer capable of minting deterministic bot subjects for the case's
  seat/player IDs, an operator token with `simulation:control`, designer tokens with
  `simulation:read` / `simulation:write`, and an observer token with
  `simulation:observe`.
- Free loopback host ports for the lane services
  (`SIMULATION_GAME_PORT`, `SIMULATION_TIMELINE_PORT`, `SIMULATION_READ_PORT`,
  defaulting to `18180`, `18181`, `18182`).

## Provision a lane

```bash
export SIMULATION_LANE=<lane> SIMULATION_EXPERIMENT=<experiment> SIMULATION_CASE=<case>
export JWT_ISSUER_URI=https://<issuer-minting-lane-identities>
export SIMULATION_GAME_PORT=18180 SIMULATION_TIMELINE_PORT=18181 SIMULATION_READ_PORT=18182
bash scripts/write-simulation-manifest.sh
bash scripts/verify-simulation-deployment.sh
docker compose -p temporal-rift-sim-$SIMULATION_LANE -f compose.yml -f compose.simulation.yml up --build -d
bash scripts/check-simulation-readiness.sh
```

`write-simulation-manifest.sh` records the lane attribution, service revisions, adopted
contract versions (including the control contract shared by game-service and
timeline-service), SHA-256 digests of the served rules/content inputs, the
`LOGICAL` timing mode, and the identity configuration without credential material. It
also retains copies of every digest input beside the manifest at
`simulation/lanes/<lane>/`, so a later reader inspects what the lane actually served.
Regenerate the manifest for every lane deployment; never hand-edit it.

`verify-simulation-deployment.sh` fails before any container starts when the lane,
issuer, overlay, contract pins, manifest, retained bundle, broker grants, or score
provenance are missing or incompatible. A bundle change after manifest generation fails
as `MANIFEST_MISMATCH` instead of deploying under a stale attribution.

The lane project is named `temporal-rift-sim-<lane>`; every lane command scopes to it
with `-p`. Lane databases, broker, and volumes are separate per project, so two lanes
never share storage, topics, or identities.

## Who can reach what

| Caller | Lane access |
|---|---|
| Bot (one deterministic subject per seat/player ID) | Normal authenticated participant APIs only |
| Operator (`simulation:control`) | Control operations (`PUT /internal/simulation/v1/execution`, `GET /internal/simulation/v1/checkpoint`, `PUT /internal/simulation/v1/clock`) plus participant APIs |
| Observer (`simulation:observe`) | Read-only consumption of that lane's `game.events` and `timeline.events` with an independent consumer group |
| Designer (`simulation:read` / `simulation:write`) | Workbench reads and writes outside the lane |

Bots cannot reach operator endpoints, observer artifacts, or other players'
credentials; each bot uses only its own participant identity. Control operations exist
only in lane deployments: ordinary deployments answer them with 404 even for a
control-scoped caller, and participant credentials receive 403. Broker grants follow
the same boundary (`scripts/provision-kafka-simulation-acls.sh`): the lane observer
reads only that lane's event topics, while bots and the operator hold no broker grants
and use HTTP only.

## Readiness before gameplay

`check-simulation-readiness.sh` requires an operator token and the expected bundle
digest. It requires both services drained on the same digest — no pending outbox,
continuation, or due-timer work — and then prints the per-source/group watermarks.
A locally drained checkpoint is not global quiescence: the runner must additionally
observe the causal messages at the downstream consumers before treating the lane as
ready. Independent topic ordering is preserved throughout.

A pre-launch bundle mismatch fails as `MANIFEST_MISMATCH`; configuration drift during
a case fails that attempt with `CONFIGURATION_DRIFT` and never counts as a result.

## Cancel and tear down

Cancellation stops new scheduling for the case; teardown removes only the named lane
project:

```bash
docker compose -p temporal-rift-sim-$SIMULATION_LANE -f compose.yml -f compose.simulation.yml down -v --remove-orphans
```

Existing developer, playtest, E2E, and production resources are never part of a lane
project and remain protected. Durable workbench evidence lives outside the lane and
remains after teardown.

## Validate without Docker

```bash
bash scripts/test-verify-simulation-deployment.sh
bash scripts/test-check-simulation-readiness.sh
```
