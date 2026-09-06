# Temporal Rift Infrastructure

Local orchestration for the Temporal Rift microservices. This repository owns the shared Compose topology; each
service owns its own Dockerfile and application configuration.

From the workspace root, where this repository is a sibling of `game-service`, `timeline-service`, and `read-service`,
start the stack with:

```bash
docker compose -f infrastructure/compose.yml up --build
```

The stack starts the three services, PostgreSQL (one database per service), Kafka, Kafka UI, Zipkin, VictoriaLogs, and
a Config Server. It also creates `game.events`, `timeline.events`, `game.commands`, and `game.dlq` with three
partitions before the services start. Each service waits for a healthy Zipkin server before starting, so its startup
spans are retained. Set `JWT_ISSUER_URI` to a reachable issuer before using authenticated game-service or read-service
endpoints.

| Local UI | URL |
|---|---|
| VictoriaLogs centralized logs | http://localhost:9428/select/vmui |
| Zipkin distributed traces | http://localhost:9411/zipkin/ |
| Kafka UI | http://localhost:8083 |
| Config Server | http://localhost:8888 |

## Shared configuration with Spring Cloud Config Server

`config-server` is a small Spring Boot app (`config-server/`, built from this repo) serving configuration values
that are meant to be shared across independently-deployed services, rather than hand-duplicated in each one. It
runs with the Spring Cloud Config Server `native` profile, reading YAML files from `config-server/config-repo/`,
which Compose bind-mounts read-only into the container — editing a file there and restarting the container (no
image rebuild) is enough to serve an updated value, since the native backend re-reads the file on every request.

It serves the card-grade probability magnitude/multiplier table and probability-band thresholds under two
namespaces at once — `game.rules.probability.*` (the shape `timeline-service`'s `TimelineRulesProperties` expects)
and `game.rules.scoring.*` (the shape `game-service`'s `ScoringRulesProperties` expects) — the same underlying
numbers served twice under each service's own pre-existing key names, so neither service had to reshape its
configuration to adopt this Config Server. Both `game-service` and `timeline-service` now consume it by default,
with a local profile document or environment variable still able to override any individual value.

Query it directly with Config Server's standard `/{application}/{profile}` convention, for example the shared
defaults everyone gets absent a more specific override:

```bash
curl http://localhost:8888/application/default
```

### Onboarding a new service as a Config Client

1. Add `org.springframework.cloud:spring-cloud-config-server`'s client counterpart,
   `org.springframework.cloud:spring-cloud-starter-config`, as a dependency (version managed by
   `temporal-rift-bom`'s `spring-cloud-dependencies` import).
2. Point the service at this Config Server, e.g. in `application.yml`:
   ```yaml
   spring:
     config:
       import: "optional:configserver:${CONFIG_SERVER_URI:http://config-server:8888}"
   ```
   `optional:` means an unreachable Config Server doesn't itself fail startup — rely on the consuming
   `@ConfigurationProperties` class's own validation to catch a genuinely incomplete configuration instead.
3. Bind the served properties the same way any other `@ConfigurationProperties` class does — no custom client code
   is required. If the new service's existing property shape doesn't already match `game.rules.probability.*` or
   `game.rules.scoring.*`, add a block under the new service's own key names to
   `config-server/config-repo/application.yml` instead of reshaping the service to match an existing namespace.

## Centralized logs with VictoriaLogs

The three application containers emit Spring Boot's native Logstash JSON to stdout. Docker forwards each line through
its `syslog` logging driver, over TCP, directly to the `victorialogs` container's syslog listener on port `514` — no
separate collector container. No VictoriaLogs client or appender is installed in the service images.

Every event includes a stable `app_name` field (from Docker's `tag` log option) identifying its source:

- `app_name:="game-service"`
- `app_name:="timeline-service"`
- `app_name:="read-service"`

Query these with [LogsQL](https://docs.victoriametrics.com/victorialogs/logsql/) at the VictoriaLogs UI or via
`GET http://localhost:9428/select/logsql/query?query=<expression>`. The JSON log body isn't parsed into structured
fields automatically — pipe the query through `| unpack_json` first to reach fields like `level`, `logger_name`,
`traceId`, and `spanId`, for example:

```text
app_name:="game-service" | unpack_json | traceId:"0123456789abcdef0123456789abcdef"
```

Copy a matching trace ID into Zipkin to inspect the corresponding span tree. Startup and some background events
legitimately have no trace fields because no tracing context exists.

VictoriaLogs stores its data in the Compose-managed `victorialogs-data` volume, so normal container recreation keeps
the log history. The existing reset command removes both `postgres-data` and `victorialogs-data`:

```bash
docker compose -f infrastructure/compose.yml down -v
```

This topology is intentionally development/showcase only. VictoriaLogs starts without authentication. Do not expose
ports `9428` or `514` from a shared or production host without designing authentication, TLS, retention, and access
control. Docker's default dual-logging cache normally keeps `docker logs` usable; VictoriaLogs is the supported
cross-service log view for this stack.

## End-to-end verification

The infrastructure repository also owns the black-box system test. It builds and starts all three services with
isolated PostgreSQL databases, Kafka topics, and a test-only OpenID Connect issuer, executes the fluent JUnit scenarios,
and removes the named Compose project and its volume afterward.

Prerequisites:

- Docker with Compose v2.24.4 or newer (the test override uses the Compose `!override` tag)
- Maven 3.9.16 or newer
- JDK 26 selected through `JAVA_HOME` and first on `PATH`
- host ports `18080`, `18082`, `15341`, and `22201` available

Run from this repository:

```bash
mvn verify -Pe2e
```

The test project is named `temporal-rift-e2e` and uses host ports `18080` (game-service), `18082` (read-service),
`15341` (VictoriaLogs UI/query), and `22201` (VictoriaLogs syslog listener), so it can run beside the normal local
stack. At the beginning of each run, only a stale `temporal-rift-e2e` project is reset. The post-integration-test
phase removes only that same project.

If Maven or the machine is interrupted before post-integration-test, recover with:

```bash
docker compose -p temporal-rift-e2e -f compose.yml -f src/test/resources/compose.e2e.yml down -v --remove-orphans
```

The OIDC private key under `src/test/resources/oidc/` is deliberately checked-in test material. It signs only the
short-lived tokens accepted by the isolated `e2e-auth` container and must never be used by a deployed environment.

### Continuous enforcement

`.github/workflows/system-e2e.yml` runs the same `mvn verify -Pe2e` command automatically. It checks out
`infrastructure`, `game-service`, `timeline-service` and `read-service` as sibling directories — the layout Compose's
`../<service>` build contexts require — so CI builds the services exactly as they are in version control.

It triggers four ways:

| Trigger | Sources used |
|---|---|
| Pull request or push to `main` in this repo | this repo at the triggering commit, the three services at `main` |
| `workflow_call` with `service` and `ref` | the named service at that ref, the other repositories at `main` |
| `workflow_call` with no inputs | every repository at `main` |
| Manual `workflow_dispatch`, with `service` and `ref` | the named service at that ref, the other repositories at `main` — for ad-hoc runs outside any pull request |

A service repository invokes it like this:

```yaml
jobs:
  system-e2e:
    uses: temporal-rift/infrastructure/.github/workflows/system-e2e.yml@main
    with:
      service: game-service
      ref: ${{ github.sha }}
```

The job is named `system-e2e`; branch protection's required check points at that name. Note this is a repository
**setting**, not something the workflow file can assert — it has to be applied once in repository settings after the
workflow has a green run on `main`.

On failure the run publishes a `system-e2e-diagnostics-*` artifact containing the Compose logs for all services,
the container state at failure, and the Failsafe/Surefire reports — enough to identify which service and which
asserted transition failed without reproducing locally. Nothing is uploaded on a green run.

The Compose logs and container state are captured by a Maven execution bound to the `e2e` profile's
`post-integration-test` phase, ordered before `stop-system-under-test`. That ordering matters: Failsafe records test
failures at the `integration-test` phase without failing the build, and only fails it later at `verify` — so the
stack is already torn down by the time a single `mvn verify -Pe2e` invocation returns control to a shell. Capturing
from the workflow after that point would find nothing; capturing inside the build, before teardown, is what makes the
artifact meaningful.

Teardown always runs, including on cancellation, and is scoped to the `temporal-rift-e2e` Compose project name, so it
removes exactly what the run created and cannot disturb any other stack on the runner.

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
| Game and era start | Accepted start; distinct game/lobby IDs; seven-card deal, five-card hand selection, faction and three-event read projections |
| Centralized logs | All three services' `app_name` visible in VictoriaLogs; at least one event has non-blank `traceId` and `spanId` |

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

### Excluded pending the card-system rework

The three-round card-play lifecycle — action rounds, forged-target and duplicate-submission rejection, faction
specials, round-close paths, timeline resolution, scoring parity, and era-two continuation — is deliberately not
simulated here. It selects valid cards and an eligible faction special, both of which the in-flight card-system rework
(game-service#121, #122, #123; timeline-service#45 — grades, playability restrictions, and the once-per-era special
budget) changes what a scenario may legally play. Writing it now would mean rewriting it once that rework lands, so it
is tracked separately in
[temporal-rift/infrastructure#7](https://github.com/temporal-rift/infrastructure/issues/7) and lands against the
reworked rules instead.
