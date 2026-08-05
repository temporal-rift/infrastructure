# Temporal Rift Infrastructure

Local orchestration for the Temporal Rift microservices. This repository owns the shared Compose topology; each
service owns its own Dockerfile and application configuration.

From the workspace root, where this repository is a sibling of `game-service`, `timeline-service`, and `read-service`,
start the stack with:

```bash
docker compose -f infrastructure/compose.yml up --build
```

The stack starts the three services, PostgreSQL (one database per service), Kafka, Kafka UI, and Zipkin. It also
creates `game.events`, `timeline.events`, `game.commands`, and `game.dlq` with three partitions before the services
start. Each service waits for a healthy Zipkin server before starting, so its startup spans are retained. Set
`JWT_ISSUER_URI` to a reachable issuer before using authenticated game-service or read-service endpoints.

To reset local PostgreSQL data:

```bash
docker compose -f infrastructure/compose.yml down -v
```

## End-to-end verification

The infrastructure repository also owns the black-box system test. It builds and starts all three services with
isolated PostgreSQL databases, Kafka topics, and a test-only OpenID Connect issuer, executes the fluent JUnit scenarios,
and removes the named Compose project and its volume afterward.

Prerequisites:

- Docker with Compose v2.24.4 or newer (the test override uses the Compose `!override` tag)
- Maven 3.9.16 or newer
- JDK 26 selected through `JAVA_HOME` and first on `PATH`
- host ports `18080` and `18082` available

Run from this repository:

```bash
mvn verify -Pe2e
```

The test project is named `temporal-rift-e2e` and uses host ports `18080` (game-service) and `18082` (read-service),
so it can run beside the normal local stack on ports `8080` and `8082`. At the beginning of each run, only a stale
`temporal-rift-e2e` project is reset. The post-integration-test phase removes only that same project.

If Maven or the machine is interrupted before post-integration-test, recover with:

```bash
docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml down -v --remove-orphans
```

The OIDC private key under `src/test/resources/oidc/` is deliberately checked-in test material. It signs only the
short-lived tokens accepted by the isolated `e2e-auth` container and must never be used by a deployed environment.

### Scenario DSL

The scenarios bind actions to named actors and keep transport mechanics in `TemporalRiftScenario`. Commands return an
HTTP transition that is asserted immediately, while eventual Kafka/outbox/projection changes are awaited through typed,
immutable snapshots:

```java
var created = scenario.as(host).createLobby().assertStatus(201);
var lobbyId = UUID.fromString(created.body().path("lobbyId").asText());

scenario.as(guest).joinLobby(lobbyId).assertStatus(200);

var round = scenario.awaitRoundState(
        host, gameId, 1, 1, state -> "OPEN".equals(state.status()) && state.submittedCount() == 1);
assertThat(round.pendingPlayerIds()).containsExactlyInAnyOrder(playerTwo.playerId(), playerThree.playerId());
```

### Covered cross-component flows

| Flow | Explicit transitions asserted |
|---|---|
| Security and privacy | Missing token → 401; non-host start → 403; outsider state read → 404; own faction/hand visible; peer factions hidden |
| Lobby lifecycle | Create; join; duplicate join rejection; insufficient-player rejection; host transfer; last-host closure |
| Game and era start | Accepted start; distinct game/lobby IDs; faction, five-card hand, three-event read projections |
| Action rounds | Forged target rejection; card acceptance; duplicate submission rejection; eligible faction special; all-submitted close; timer close with a skipped player |
| Timeline and scoring | Round 3 → resolution; terminal outcomes; three-player score publication; game-service/read-service score parity |
| Era continuation | Era 2 projection contains a replaced five-card hand and a new three-event set |

The system test intentionally complements, rather than duplicates, exhaustive aggregate and adapter tests in each
service. It concentrates on behavior that crosses process, database, or Kafka boundaries.

### Explicitly excluded incomplete flows

These documented surfaces do not yet have a complete production path and are not simulated as passing E2E behavior:

- paradox-resolution action REST and paradox-resolution saga interaction
- timeline-service Scan probability-state and Weaver-chain REST endpoints
- read-service history endpoint and WebSocket notification/filtering
- disconnect/reconnect initiation from the absent WebSocket notification path
- paradox cascade, chain, timeline collapse, timeline stabilization, and final faction reveal as complete player journeys

Their existing service-local tests remain authoritative for implemented internal slices until the missing public or
cross-service path is delivered.
