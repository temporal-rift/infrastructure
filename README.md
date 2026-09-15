# Temporal Rift Infrastructure

Local orchestration for the Temporal Rift microservices. This repository owns the shared Compose topology; each
service owns its own Dockerfile and application configuration.

From the workspace root, where this repository is a sibling of `game-service`, `timeline-service`, and `read-service`,
start the stack with:

```bash
docker compose -f infrastructure/compose.yml up --build
```

The stack starts the three services, PostgreSQL (one database per service), Kafka, Kafka UI, Zipkin, VictoriaLogs, a
Config Server, and the metrics/dashboards/alerting stack described below. It also creates `game.events`,
`timeline.events`, `game.commands`, and one source-specific
dead-letter topic for each of them, all with three
partitions before the services start. Each service waits for a healthy Zipkin server before starting, so its startup
spans are retained. Set `JWT_ISSUER_URI` to a reachable issuer before using authenticated game-service or read-service
endpoints.

| Local UI | URL |
|---|---|
| VictoriaLogs centralized logs | http://localhost:9428/select/vmui |
| Zipkin distributed traces | http://localhost:9411/zipkin/ |
| Kafka UI | http://localhost:8083 |
| Config Server | http://localhost:8888 |
| Grafana dashboards | http://localhost:3000 |
| VictoriaMetrics | http://localhost:8428 |
| vmalert | http://localhost:8880 |
| Alertmanager | http://localhost:9093 |

## Kafka topic security, retention, and data lifecycle

<!-- kafka-topology:start -->
| Topic | Channel class | Purpose |
|---|---|---|
| `game.events` | Domain events | Carries events produced by game-service for timeline-service and read-service. |
| `timeline.events` | Domain events | Carries resolved timeline events for game-service and read-service. |
| `game.commands` | Commands | Carries commands for game-service. |
| `game.events.dlq` | Dead-letter | Holds game event records that timeline-service could not process after retries. |
| `timeline.events.dlq` | Dead-letter | Holds timeline event records that game-service could not process after retries. |
| `game.commands.dlq` | Dead-letter | Holds command records that game-service could not process after retries. |
<!-- kafka-topology:end -->

`compose.yml`'s topic-provisioning step (`scripts/provision-kafka-topics.sh`) pins an explicit `retention.ms` on
every topic instead of leaving it at the broker default, matching its class: `game.events` and `timeline.events`
(domain replay window, 7 days), `game.commands` (transient commands, 1 day), and each source-specific dead-letter
topic (30 days — long enough to investigate a parked poison message). The script re-applies retention on every run, so a
topic that already exists with a different value is reconciled rather than left as-is. CI validates this table against
the provisioner in both directions, so adding or removing a topic requires updating both declarations.

Local development stays plaintext with no broker authentication or authorization by design — that friction has no
place in an inner dev loop. `compose.secure.yml` is a wholly separate, standalone Compose stack (own broker, own
ports, own project name `temporal-rift-secure`) that demonstrates the authenticated, least-privilege topology
intended for any environment outside local development: SASL/PLAIN authentication and a deny-by-default KRaft
`StandardAuthorizer`, with `scripts/provision-kafka-acls.sh` granting each service identity only the topics and
consumer groups its own code actually uses — no wildcards. It never runs as part of the local dev stack; bring it
up on its own to inspect it:

```bash
docker compose -f compose.secure.yml up --build --wait
```

The `security-e2e` Maven profile (`mvn verify -Psecurity-e2e`, from this repository) boots that stack and proves
it automatically: an identity's authorized produce/consume succeeds, an identity's attempt outside its granted set
fails with `TopicAuthorizationException`, that denial is recorded in the broker's own `kafka-authorizer.log`
naming the principal and resource, and every topic's retention matches its documented class. The SASL/PLAIN
credentials in `compose.secure.yml` are local/CI-only demo values that exist only in that file — a real deployment
outside local development would source real credentials from a secrets manager and very likely add TLS
(`SASL_SSL`) underneath, both intentionally out of scope for this demonstration.

### Erasing one player's data

An erasure request for a player must be followed through every carrier of player-identifying event payloads:

| Carrier | How the player's data is removed |
|---|---|
| `game.events`, `timeline.events`, `game.commands` | Self-expiring: no action needed once the topic's retention window (see above) elapses. There is no compaction or manual tombstoning of these topics — a request that cannot wait out the retention window is not satisfiable by these topics alone. |
| `game.events.dlq`, `timeline.events.dlq`, `game.commands.dlq` | Same as above, on their own 30-day window. |
| `game-service`'s database | Durable — does not expire on its own. Delete the player's rows from every table that references their player id (lobby membership, hand/selection state, action history, score records) via that service's own migrations/tooling; do not rely on retention. |
| `timeline-service`'s database | Durable. Its event-sourced store retains `FutureEvent` history; delete or redact rows referencing the player's id the same way. |
| `read-service`'s database | Durable. Delete the player's projection rows (game state, game history, player-game-state) the same way. |
| Centralized logs (VictoriaLogs) | Durable but not indexed by player id — a targeted deletion requires a manual LogsQL query against the player's known identifiers (game/player UUIDs) followed by VictoriaLogs' own deletion API; there is no automatic per-player purge. |

## Shared configuration with Spring Cloud Config Server

`config-server` is a small Spring Boot app (`config-server/`, built from this repo) serving configuration values
that are meant to be shared across independently-deployed services, rather than hand-duplicated in each one. It
runs with the Spring Cloud Config Server `native` profile, reading YAML files from `config-server/config-repo/`,
which Compose bind-mounts read-only into the container — editing a file there and restarting the container (no
image rebuild) is enough to serve an updated value, since the native backend re-reads the file on every request.

It serves the card-grade probability magnitude table and probability-band thresholds under one namespace,
`game.rules.probability.*` — `push-shift`, `suppress-shift`, and `swing-shift`, each a map keyed by card grade
(`I`/`II`/`III`), plus `amplify-multiplier` and the band/floor threshold fields. Both `game-service` and
`timeline-service` bind this same namespace directly; each service's own `@ConfigurationProperties` class
declares only the fields it needs (e.g. `game-service` has no use for `amplify-multiplier`, since `AMPLIFY`
never shifts its band preview) and Spring simply ignores the rest — there is exactly one served copy of these
values, not one per consuming service. A local profile document or environment variable can still override any
individual value.

Query it directly with Config Server's standard `/{application}/{profile}` convention, for example the shared
defaults everyone gets absent a more specific override:

```bash
curl http://localhost:8888/application/default
```

Alongside `application.yml`, the config repo also has one file per service —
`config-server/config-repo/game-service.yml`, `timeline-service.yml`, and `read-service.yml` — served only to the
Config Client whose `spring.application.name` matches the file's base name, layered on top of the shared
`application.yml` values. `read-service` is onboarded and reads `notification.websocket.*` from here; its own
`application.yml` no longer hard-codes those values. `game-service.yml` and `timeline-service.yml` still hold a
copy of app-level tunables (e.g. `game.rate-limit`, `game.timers`, `game.rules.*`) that each of those services'
own `application.yml` also still hard-codes today — actually switching either of them over to read its copy from
here instead of its local file is a separate, per-service migration.

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
   is required. Prefer binding directly against an existing namespace like `game.rules.probability.*` (declaring
   only the fields the new service actually needs) over introducing a parallel namespace for the same
   underlying values — a second namespace means a second copy to keep in sync by hand, which is exactly what
   this Config Server exists to avoid. Add a genuinely new block to `config-server/config-repo/application.yml`
   only when the new service needs values no existing namespace already serves.

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

## Metrics, dashboards, and alerts

The stack scrapes Kafka-broker-level metrics (via a `kafka-exporter` sidecar) and each service's application
metrics into VictoriaMetrics, renders them on provisioned Grafana dashboards, and evaluates configuration-driven
alert rules with vmalert. Config lives under `observability/` in this repository:

| Component | Config |
|---|---|
| VictoriaMetrics scrape targets | `observability/victoriametrics/scrape.yml` |
| Grafana datasource/dashboard provisioning | `observability/grafana/provisioning/`, dashboards in `observability/grafana/dashboards/` |
| Alert rules and thresholds | `observability/vmalert/rules.yml` |
| Alertmanager routing | `observability/alertmanager/alertmanager.yml` |

**Available today, no service-side change required** — `kafka-exporter` reads consumer-group and topic offsets
directly from the broker, so these work as soon as the stack is up:

- **Consumer lag**, by consumer group, topic, and partition (`kafka_consumergroup_lag`) — Grafana's "Consumer lag"
  dashboard, alerted by `KafkaConsumerGroupLagHigh` when a group's lag exceeds the threshold in `rules.yml`.
- **Dead-letter traffic**, on `game.events.dlq`, `timeline.events.dlq`, and `game.commands.dlq`
  (`kafka_topic_partition_current_offset`) — Grafana's "Dead-letter traffic" dashboard, alerted by
  `KafkaDeadLetterTrafficDetected` on any offset increase.

**Pending a linked cross-repo dependency** — `game-service`'s era-saga sweep-recovery counter and
`timeline-service`'s and `read-service`'s Kafka consumer-skip counters already exist, but none of the three
services yet expose a Prometheus scrape endpoint (`GET /actuator/prometheus`) — see
[temporal-rift/infrastructure#37](https://github.com/temporal-rift/infrastructure/issues/37)'s Cross-Repo
Dependencies, and the linked `game-service#200`, `timeline-service#101`, `read-service#88`. Grafana's "Version
skips" and "Sweep recoveries" dashboards, and their scrape jobs in `scrape.yml`, are already wired to the correct
metric names and will show data the moment each dependency lands — no dashboard or infra change needed then.

Change an alert threshold by editing `observability/vmalert/rules.yml` and restarting the `vmalert` container — it
is not a code constant. Validate a rule change without a running broker:

```bash
bash scripts/verify-alert-rules.sh
```

This topology is intentionally development/showcase only, matching the rest of this stack's posture: Grafana runs
with anonymous admin access enabled, and no component here integrates real paging (email, Slack, PagerDuty). A
firing alert is observable through Alertmanager's own API (`GET http://localhost:9093/api/v2/alerts`), not
delivered anywhere external. Do not expose these ports from a shared or production host without designing
authentication and real notification routing first.

## End-to-end verification

The infrastructure repository also owns the black-box system test. It builds and starts all three services with
isolated PostgreSQL databases, Kafka topics, and a test-only OpenID Connect issuer, executes the fluent JUnit scenarios,
and removes the named Compose project and its volume afterward.

Prerequisites:

- Docker with Compose v2.24.4 or newer (the test override uses the Compose `!override` tag)
- Maven 3.9.16 or newer
- JDK 26 selected through `JAVA_HOME` and first on `PATH`
- host ports `18080`, `18082`, `15341`, `22201`, `19092`, `19308`, and `19411` available

Run from this repository:

```bash
mvn verify -Pe2e
```

The test project is named `temporal-rift-e2e` and uses host ports `18080` (game-service), `18082` (read-service),
`15341` (VictoriaLogs UI/query), `22201` (VictoriaLogs syslog listener), `19092` (Kafka, for test-only
fault-injection/probe clients), `19308` (kafka-exporter, for the dead-letter-traffic proof), and `19411` (Zipkin,
for the trace-continuity proof), so it can run beside the normal local stack. At the beginning of each run, only a stale `temporal-rift-e2e` project is reset. The post-integration-test
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
| Action rounds | Dynamic, round-eligible card and faction-special selection; duplicate-submission and forged-event-target rejection; once-per-era special budget accepted once, rejected on same-era reuse, and accepted again next era; both round-close paths (all-submitted and timer) |
| Timeline and scoring | Action-round resolution; `game-service` and `read-service` scores agree after era completion |
| Era continuation | Era two projects three active events and a fresh five-card hand (not accumulated from era one), with a durable history record; cascaded events may legitimately carry over |
| Game end and faction reveal | Game reaches a terminal state (win, collapse, or stabilization) via public entry points only; `game-service` and `read-service` final scores and revealed factions agree; every player's faction is null until `FactionRevealed` and populated for all players afterward; game history is durable through the final era |
| Multi-target Scan intel | Grade II/III `SCAN` reaches only the scanning player, refreshes each round, including a round in which nobody acted at all, agrees with the real public band for the same state, and is suppressed by a same-round Nullify or a Stall on the covered event; reconnect preserves it without leaking to another player; era end and direct game end clear it; a delayed prior-era reveal and a replayed message never resurrect or duplicate it — the last two verified by publishing/observing real `timeline.events` traffic directly (`KafkaFaultInjector`/`KafkaEventProbe`), since Kafka is a declared service-to-service boundary for this test, not just REST |
| Centralized logs | All three services' `app_name` visible in VictoriaLogs; at least one event has non-blank `traceId` and `spanId` |
| Trace continuity | One Zipkin trace links spans from `game-service`, `timeline-service`, and `read-service` for a game-start flow that crosses all three |
| Dead-letter observability | A record published directly to `game.events.dlq` (`KafkaFaultInjector`) is reflected in `kafka-exporter`'s offset metric for that topic |

The system test intentionally complements, rather than duplicates, exhaustive aggregate and adapter tests in each
service. It concentrates on behavior that crosses process, database, or Kafka boundaries.

### Explicitly excluded incomplete flows

These documented surfaces do not yet have a complete production path and are not simulated as passing E2E behavior:

- paradox-resolution action REST and paradox-resolution saga interaction
- Weaver-chain REST endpoints
- read-service WebSocket notification/filtering (including `AdjustedBandsPublished` fan-out/filtering — the
  Scan-intel scenario observes the real band directly off `timeline.events` instead, see above)
- disconnect/reconnect initiation from the absent WebSocket notification path
- paradox cascade and Weaver-chain accumulation as complete player journeys (the game-end scenario lets paradoxes
  cascade opportunistically but does not force a chain or a specific collapse/stabilization outcome)

Their existing service-local tests remain authoritative for implemented internal slices until the missing public or
cross-service path is delivered.
