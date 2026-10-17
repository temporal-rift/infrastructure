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
- Python 3 with PyYAML (`pip install pyyaml`) to resolve the lane's effective rules
  document.

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
timeline-service), the resolved effective rules document and its digests, the content
digest, the `LOGICAL` timing mode, and the identity configuration without credential
material. It also retains the canonical `effective-rules.json` and copies of every other
digest input beside the manifest at `simulation/lanes/<lane>/`, so a later reader
inspects what the lane actually served. Regenerate the manifest for every lane
deployment; never hand-edit it.

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
control-scoped caller, and participant credentials receive 403. That credential and
scope boundary is enforced by the services. The broker boundary is structural: each
lane project starts its own single-tenant broker, topics, and volumes, so cross-lane
broker access is impossible by construction. The per-role grant table the lane
observer is limited to — read-only consumption of that lane's `game.events` and
`timeline.events` with an independent consumer group, and no broker grants for bots
or the operator — is defined in `scripts/provision-kafka-simulation-acls.sh` and
checked by `verify-simulation-deployment.sh` as a contract; it is enforced by the
broker only on a hardened lane deployment running an authorizer, which the delivered
plaintext lane topology does not start.

The lane publishes no broker host ports, so the observer joins the lane network
explicitly, for example as an additional service in the lane project or with:

```bash
docker run --network temporal-rift-sim-$SIMULATION_LANE_default <observer-image>
```

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

## Observing lanes from a workbench

`compose.simulation-observer.yml` lets one workbench reach several lanes. Layer it after `compose.simulation.yml`
(`-f compose.yml -f compose.simulation.yml -f compose.simulation-observer.yml`) with `SIMULATION_OBSERVER_NETWORK`
naming an existing shared network, `SIMULATION_ISSUER_JWKS_URI` naming the issuer's key set as reachable from the lane,
and `SIMULATION_KAFKA_PORT` for loopback access to the lane broker from the host. The lane's game, timeline and read
services join the shared network as `game.<lane>.lane`, `timeline.<lane>.lane` and `read.<lane>.lane`; the lane broker
gets an observer listener that a small forwarder exposes as `kafka.<lane>.lane:9094`, so the broker's own service name
never appears on the shared network (it would resolve to every lane's broker). Service images are tagged
`temporal-rift/sim-*:${SIMULATION_IMAGE_TAG:-local}` so one build serves every lane.

## Configuration bundles

A lane serves exactly one bundle: the directory named by `SIMULATION_CONFIG_REPO` (default the shared config
repository), mounted into that lane's config server. `scripts/prepare-simulation-bundle.sh --out DIR [--max-eras N]
[--win-score-threshold T]` makes a variant, failing if a requested edit matches nothing.

The manifest writer resolves every `game.rules.*` leaf for each service by layering
`game-service.yml`, `timeline-service.yml`, or `read-service.yml` over `application.yml`, flattens nested maps with
`.`, and refuses a key two services resolve differently as `RULES_CONFLICT`, naming the key and both services. The
result is one `EffectiveRules` document `{schemaVersion: 1, properties}`, retained as
`simulation/lanes/<lane>/effective-rules.json` in RFC 8785 canonical form.

`effectiveRulesDigest` is the canonical digest of `properties` (the rules meaning the workbench materialises), while
`rulesArtifactDigest` is the SHA-256 of the retained file bytes (the stored artifact). The two intentionally differ.
`effectiveContentDigest` and `contentArtifactDigest` still describe the event catalog. `verify-simulation-deployment.sh`
recomputes the document from the bundle, compares the retained bytes, and checks both rules digests and the content
digest, failing as `MANIFEST_MISMATCH` after any drift.

## Lane provenance declared to the workbench

`scripts/provision-workbench-e2e.sh` writes the workbench's lane configuration. Besides `rules-digest` and
`content-digest`, each lane entry declares `rules-document` (its retained `effective-rules.json`), `rules-artifact-digest`
and `content-artifact-digest` (from the manifest), `services` (`game-service`, `timeline-service` and `read-service`,
each with the built image's `image-digest` and the manifest's `source-revision`) and `contracts` (the manifest's exact
version of every participant and event module; the operator's `simulation-control-api` is not declared). The lane
manifest directory is mounted read-only at `/etc/workbench/lanes`, and `rules-document` points at the mounted file.
Provisioning fails naming the lane and `rules-document` when that file is missing or its canonical `properties`
digest differs from `rules-digest`; it fails naming the lane and the key (for example
`lane wbe2e-1: services.game-service.source-revision`) when any other value is missing or malformed, and when two
lanes serving the same bundle would declare different provenance. The system tests cite `services` and `contracts`
from the bundles the workbench lists at `GET /api/v1/lane-bundles`.

## Recycling a used lane

A lane accepts only one execution context for its lifetime. `scripts/recycle-simulation-lane.sh <lane-env-file>`
removes that lane's own project (`down -v`) and starts it again from the images it has, answering only after the
services are healthy; the env file holds the same `SIMULATION_*` variables the lane was provisioned with. Nothing outside
the named project is touched.
