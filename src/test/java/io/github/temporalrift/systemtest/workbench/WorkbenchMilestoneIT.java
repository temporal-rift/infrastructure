package io.github.temporalrift.systemtest.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.systemtest.workbench.ReportMath.CaseRow;

/**
 * The deployed milestone proof, driven through the workbench's generated HTTP interface against real lanes.
 * Tests run in order and share the runs they create: the seed-42 matrix, a multi-seed fixture with an
 * induced lane fault, a restart with a reproduction, and the idempotency and redelivery checks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkbenchMilestoneIT {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Duration MATRIX_TIMEOUT = minutes("workbenchE2e.matrixTimeoutMinutes", 360);
    private static final Duration FIXTURE_TIMEOUT = minutes("workbenchE2e.fixtureTimeoutMinutes", 180);
    private static final int CASE_TIMEOUT_SECONDS = Integer.getInteger("workbenchE2e.caseTimeoutSeconds", 900);
    private static final int CANDIDATE_THRESHOLD = 22;
    private static final List<String> MULTI_SEEDS =
            List.of(System.getProperty("workbenchE2e.multiSeeds", "1,2,3,4").split(","));

    private final Deployment deployment = Deployment.load();
    private final Issuer issuer = new Issuer(deployment.issuer());
    private final Workbench designer =
            new Workbench(deployment.workbenchUrl(), issuer.token("designer-api", Issuer.DESIGNER));
    private final Workbench analyst =
            new Workbench(deployment.workbenchUrl(), issuer.token("analyst-api", Issuer.ANALYST));
    private final Workbench bot = new Workbench(
            deployment.workbenchUrl(),
            issuer.token(deployment.lanes().getFirst().bots().getFirst().toString(), null));
    private final Evidence evidence = new Evidence("core");
    private final int lanes = deployment.lanes().size();

    private JsonNode policies;
    private String matrixExperimentId;
    private String matrixRunId;
    private List<String> previewKeys;
    private List<CaseRow> matrixRows;
    private Instant matrixStarted;
    private Duration matrixDuration;

    private String multiRunId;
    private List<CaseRow> multiRows;
    private JsonNode failedCase;

    private static Duration minutes(String property, int fallback) {
        return Duration.ofMinutes(Integer.getInteger(property, fallback));
    }

    @AfterAll
    void writeEvidence() {
        evidence.write(deployment);
    }

    // ------------------------------------------------------------------------------------------------------
    // The seed-42 matrix
    // ------------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void thePreviewListsAllTwoHundredTwentyCoordinates() {
        policies = designer.policies();
        evidence.record("policies", policies);
        var manifest = Manifests.build(deployment, policies, matrixSpec());

        var preview = designer.post("/api/v1/experiment-previews?limit=500", manifest, null)
                .requireStatus(200)
                .json();

        assertThat(preview.path("caseCount").asInt()).isEqualTo(220);
        var perVariantAndSize = new LinkedHashMap<String, Integer>();
        preview.path("breakdown")
                .forEach(entry -> perVariantAndSize.put(
                        entry.path("variantLabel").asString() + "/"
                                + entry.path("playerCount").asInt(),
                        entry.path("caseCount").asInt()));
        assertThat(perVariantAndSize)
                .containsEntry("baseline/3", 60)
                .containsEntry("baseline/4", 40)
                .containsEntry("baseline/5", 10)
                .containsEntry("candidate/3", 60)
                .containsEntry("candidate/4", 40)
                .containsEntry("candidate/5", 10);

        var keys = new ArrayList<String>();
        preview.path("cases").forEach(entry -> keys.add(entry.path("caseKey").asString()));
        assertThat(keys).hasSize(220).doesNotHaveDuplicates();
        previewKeys = keys;
    }

    @Test
    @Order(2)
    void aFrozenExperimentIsLaunchedAsADurableRun() {
        var manifest = Manifests.build(deployment, policies, matrixSpec());
        var key = UUID.randomUUID();

        var experiment = designer.createExperiment(manifest, key);
        matrixExperimentId = experiment.path("experimentId").asString();
        assertThat(experiment.path("manifestDigest").asString()).matches(SHA256);
        assertThat(designer.createExperiment(manifest, key).path("experimentId").asString())
                .as("repeating the same request returns the original experiment")
                .isEqualTo(matrixExperimentId);

        var run = designer.startRun(matrixExperimentId, UUID.randomUUID());
        matrixRunId = run.path("runId").asString();
        matrixStarted = Instant.now();
        assertThat(run.path("counts").path("requested").asInt()).isEqualTo(220);
        assertThat(run.path("counts").path("pending").asInt()).isEqualTo(220);
    }

    @Test
    @Order(3)
    void everyCoordinateEndsAccountedForWithAnAuthoritativeResultOrAnExplicitReason() {
        var run = designer.awaitRun(
                matrixRunId, Workbench::isFinal, MATRIX_TIMEOUT, "the seed-42 matrix to reach a final state");
        matrixDuration = Duration.between(matrixStarted, Instant.now());
        var counts = run.path("counts");
        assertThat(run.path("state").asString()).isEqualTo("COMPLETED");
        assertThat(counts.path("requested").asInt()).isEqualTo(220);
        assertThat(counts.path("pending").asInt()).isZero();
        assertThat(counts.path("running").asInt()).isZero();
        assertThat(counts.path("succeeded").asInt()
                        + counts.path("failed").asInt()
                        + counts.path("cancelled").asInt())
                .as("no requested case may vanish or be silently replaced")
                .isEqualTo(220);

        var summaries = designer.caseSummaries(matrixRunId, null, null);
        assertThat(summaries.stream().map(summary -> summary.path("caseKey").asString()))
                .containsExactlyInAnyOrderElementsOf(previewKeys);

        var rows = new ArrayList<CaseRow>();
        var table = new ArrayList<Map<String, Object>>();
        for (var summary : summaries) {
            var detail = designer.caseDetail(matrixRunId, summary.path("caseId").asString());
            var row = CaseRow.of(detail);
            rows.add(row);
            table.add(accountFor(detail, row));
        }
        matrixRows = List.copyOf(rows);
        evidence.record("matrixCases", table);
        evidence.record(
                "matrixCounts",
                Map.of(
                        "requested", counts.path("requested").asInt(),
                        "succeeded", counts.path("succeeded").asInt(),
                        "failed", counts.path("failed").asInt(),
                        "cancelled", counts.path("cancelled").asInt()));
        evidence.record("matrixRuntimeSeconds", matrixDuration.toSeconds());
        evidence.record(
                "matrixThroughputCasesPerHour",
                matrixDuration.isZero() ? 0 : Math.round(220.0 * 3600 / matrixDuration.toSeconds() * 10) / 10.0);

        for (var variant : List.of(Manifests.BASELINE, Manifests.CANDIDATE)) {
            assertThat(rows.stream().filter(row -> row.variant().equals(variant) && row.succeeded()))
                    .as("variant %s needs at least one authoritative game to be comparable", variant)
                    .isNotEmpty();
        }
        assertEachVariantPlayedItsOwnBundle(rows);
    }

    private Map<String, Object> accountFor(JsonNode detail, CaseRow row) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("caseKey", detail.path("caseKey").asString());
        entry.put("variant", row.variant());
        entry.put("seed", row.seed());
        entry.put("playerCount", row.playerCount());
        entry.put("policy", row.policyId());
        entry.put("state", row.state());
        if (row.succeeded()) {
            var result = detail.path("result");
            assertThat(row.endReason()).as("case %s ending", row.caseId()).isNotBlank();
            assertThat(row.scores()).as("case %s final scores", row.caseId()).isNotEmpty();
            assertThat(result.path("semanticDigest").asString()).matches(SHA256);
            entry.put("endReason", row.endReason());
            entry.put("winners", row.winners());
            entry.put("finalScores", row.scores());
            entry.put("semanticDigest", result.path("semanticDigest").asString());
        } else {
            var attempts = detail.path("attempts");
            var failure = attempts.path(attempts.size() - 1).path("failure");
            assertThat(failure.path("code").asString())
                    .as("case %s (%s) needs an explicit failure reason", row.caseId(), row.state())
                    .isNotBlank();
            entry.put("failureCode", failure.path("code").asString());
            entry.put("failureMessage", failure.path("message").asString());
        }
        return entry;
    }

    /** A winner by score threshold cannot be below the threshold of the bundle the variant ran under. */
    private void assertEachVariantPlayedItsOwnBundle(List<CaseRow> rows) {
        var decided = new LinkedHashMap<String, Integer>();
        for (var row : rows) {
            if (row.succeeded() && "WIN_CONDITION_MET".equals(row.endReason())) {
                var best = row.scores().values().stream()
                        .mapToInt(Integer::intValue)
                        .max()
                        .orElse(0);
                decided.merge(row.variant(), 1, Integer::sum);
                if (row.variant().equals(Manifests.CANDIDATE)) {
                    assertThat(best)
                            .as(
                                    "a candidate win condition was met below the candidate threshold in case %s",
                                    row.caseId())
                            .isGreaterThanOrEqualTo(CANDIDATE_THRESHOLD);
                }
            }
        }
        evidence.record("winConditionGamesByVariant", decided);
        deployment.lanes().forEach(lane -> assertThat(lane.rulesDigest()).matches(SHA256));
        assertThat(deployment.variantLane(Manifests.BASELINE).rulesDigest())
                .as("the two variants serve different rules bundles")
                .isNotEqualTo(deployment.variantLane(Manifests.CANDIDATE).rulesDigest());
    }

    @Test
    @Order(4)
    void theReportMatchesTheGamesAndExportsDeterministically() {
        var json = designer.get("/api/v1/runs/" + matrixRunId + "/report").requireStatus(200);
        var report = json.json();
        assertThat(report.path("runId").asString()).isEqualTo(matrixRunId);
        assertThat(report.path("analysisVersion").asString()).isNotBlank();
        evidence.record(
                "analysis",
                Map.of(
                        "analysisVersion", report.path("analysisVersion").asString(),
                        "analysisSeed", report.path("analysisSeed").asString(),
                        "formatVersion", report.path("formatVersion").asInt()));

        assertCohortsMatchTheGames(report, matrixRows, 1);

        assertThat(designer.get("/api/v1/runs/" + matrixRunId + "/report").body())
                .as("the same results always render the same report")
                .isEqualTo(json.body());
        var csv = designer.getAs("/api/v1/runs/" + matrixRunId + "/report", "text/csv")
                .requireStatus(200);
        assertThat(csv.body()).contains(matrixRunId);
        assertThat(designer.getAs("/api/v1/runs/" + matrixRunId + "/report", "text/csv")
                        .body())
                .isEqualTo(csv.body());
    }

    /**
     * Every pooled cohort's figures equal the figures recomputed from the case results: eligible games, wins,
     * win rate and shared-win rate per faction, and, with fewer than two independent blocks, no interval.
     */
    private void assertCohortsMatchTheGames(JsonNode report, List<CaseRow> rows, int expectedBlocksIfUniform) {
        var tallies = ReportMath.factionTallies(rows);
        var checked = 0;
        for (var entry : tallies.entrySet()) {
            var key = entry.getKey();
            var tally = entry.getValue();
            var cohort = ReportMath.pooledCohort(report, key.variant(), key.policyId(), key.playerCount());
            assertThat(cohort).as("pooled cohort for %s", key).isNotNull();
            var blocks = ReportMath.independentBlocks(rows, key.variant(), key.policyId(), key.playerCount());
            assertThat(cohort.path("independentBlocks").asInt())
                    .as("blocks of %s", key)
                    .isEqualTo(blocks);
            if (expectedBlocksIfUniform > 0) {
                assertThat(blocks).isEqualTo(expectedBlocksIfUniform);
            }

            var wins = ReportMath.factionMetric(cohort, "faction_win_rate", key.faction());
            assertThat(wins).as("faction_win_rate of %s", key).isNotNull();
            assertThat(wins.path("denominator").asInt()).as("games of %s", key).isEqualTo(tally.games());
            assertThat(wins.path("numerator").asDouble()).as("wins of %s", key).isEqualTo(tally.wins());
            assertThat(wins.path("value").asDouble()).isCloseTo(tally.winRate(), within(1e-9));

            var shared = ReportMath.factionMetric(cohort, "faction_shared_win_rate", key.faction());
            assertThat(shared).as("faction_shared_win_rate of %s", key).isNotNull();
            assertThat(shared.path("numerator").asDouble())
                    .as("shared wins of %s", key)
                    .isEqualTo(tally.sharedWins());
            assertThat(shared.path("value").asDouble()).isCloseTo(tally.sharedWinRate(), within(1e-9));

            for (var metric : List.of(wins, shared)) {
                if (blocks < 2) {
                    assertThat(metric.path("status").asString()).isEqualTo("INSUFFICIENT_SAMPLE");
                    assertThat(metric.path("interval").isNull()).isTrue();
                } else {
                    assertThat(metric.path("status").asString()).isEqualTo("AVAILABLE");
                    var interval = metric.path("interval");
                    assertThat(interval.path("level").asDouble()).isEqualTo(0.95);
                    assertThat(interval.path("method").asString()).isEqualTo("BLOCK_BOOTSTRAP");
                    assertThat(interval.path("lower").asDouble())
                            .isLessThanOrEqualTo(interval.path("upper").asDouble());
                }
            }
            checked++;
        }
        assertThat(checked).isPositive();
    }

    @Test
    @Order(5)
    void aPlayerReplayHidesOpposingHandsAndAnObserverReplayNeedsTheObserveScope() {
        var saved = matrixRows.stream()
                .filter(row -> row.succeeded() && row.playerCount() == 3)
                .findFirst()
                .orElseThrow();
        var seats = saved.factions().size();
        var perSeat = new ArrayList<Set<String>>();
        for (var seat = 0; seat < seats; seat++) {
            var steps = designer.replay(matrixRunId, saved.caseId(), "PLAYER", seat);
            assertThat(steps).as("player view of seat %d", seat).isNotEmpty();
            assertThat(steps)
                    .noneMatch(step -> "EVENT".equals(step.path("phase").asString()));
            var ids = new TreeSet<String>();
            steps.forEach(step -> collect(step.path("observations"), "cardInstanceId", ids));
            perSeat.add(ids);
            var ownFaction = saved.factions().get(seat);
            steps.forEach(step -> {
                var faction = step.path("observations").path("faction");
                if (!faction.isMissingNode() && !faction.isNull()) {
                    assertThat(faction.asString()).isEqualTo(ownFaction);
                }
            });
        }
        for (var first = 0; first < seats; first++) {
            for (var second = first + 1; second < seats; second++) {
                var shared = new HashSet<>(perSeat.get(first));
                shared.retainAll(perSeat.get(second));
                assertThat(shared)
                        .as("seats %d and %d must not share a hand card in their entitled views", first, second)
                        .isEmpty();
            }
        }

        var observer = designer.replay(matrixRunId, saved.caseId(), "OBSERVER", null);
        assertThat(observer).anyMatch(step -> "EVENT".equals(step.path("phase").asString()));
        evidence.record(
                "replayLogicalTime",
                Map.of(
                        "firstStep", observer.getFirst().path("logicalTime").asString(),
                        "lastStep", observer.getLast().path("logicalTime").asString()));
        var observerText = observer.toString();
        perSeat.forEach(ids -> ids.forEach(id -> assertThat(observerText).contains(id)));

        var path = "/api/v1/runs/" + matrixRunId + "/cases/" + saved.caseId() + "/replay?perspective=OBSERVER";
        assertThat(analyst.get(path).status())
                .as("analyst without observe scope")
                .isEqualTo(403);
        assertThat(bot.get(path).status()).as("bot credential").isEqualTo(403);
        assertThat(analyst.get(path).body())
                .doesNotContain(perSeat.getFirst().iterator().next());
    }

    private static void collect(JsonNode node, String field, Set<String> into) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                if (entry.getKey().equals(field) && entry.getValue().isString()) {
                    into.add(entry.getValue().asString());
                }
                collect(entry.getValue(), field, into);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, field, into));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // The multi-seed fixture, with a lane fault
    // ------------------------------------------------------------------------------------------------------

    @Test
    @Order(6)
    void multiSeedReportsAndComparisonsAreTruthfulAndShowTheFailedCounterpart() {
        var spec = new Manifests.Spec(
                "multi-seed fixture " + deployment.runId(),
                List.of(Manifests.BASELINE, Manifests.CANDIDATE),
                MULTI_SEEDS,
                List.of(3),
                List.of(List.of("ERASERS", "PROPHETS", "WEAVERS")),
                List.of(Manifests.RANDOM, Manifests.GREEDY),
                lanes,
                CASE_TIMEOUT_SECONDS);
        var experiment = designer.createExperiment(Manifests.build(deployment, policies, spec), UUID.randomUUID());
        multiRunId = designer.startRun(experiment.path("experimentId").asString(), UUID.randomUUID())
                .path("runId")
                .asString();

        failedCase = failOneRunningCandidateCase(multiRunId);

        var run =
                designer.awaitRun(multiRunId, Workbench::isFinal, FIXTURE_TIMEOUT, "the multi-seed fixture to finish");
        assertThat(run.path("state").asString()).isEqualTo("COMPLETED");
        var expectedCases = MULTI_SEEDS.size() * 3 * 2 * 2;
        assertThat(run.path("counts").path("requested").asInt()).isEqualTo(expectedCases);
        assertThat(run.path("counts").path("failed").asInt()).isGreaterThanOrEqualTo(1);

        var rows = new ArrayList<CaseRow>();
        for (var summary : designer.caseSummaries(multiRunId, null, null)) {
            rows.add(CaseRow.of(
                    designer.caseDetail(multiRunId, summary.path("caseId").asString())));
        }
        multiRows = List.copyOf(rows);

        var report = designer.get("/api/v1/runs/" + multiRunId + "/report").requireStatus(200);
        assertThat(report.json().path("complete").asBoolean()).isFalse();
        assertCohortsMatchTheGames(report.json(), multiRows, 0);
        assertThat(designer.get("/api/v1/runs/" + multiRunId + "/report").body())
                .isEqualTo(report.body());

        assertTheComparisonShowsTheFailureAndOmitsItFromThePairedEstimate();
    }

    /**
     * Stops the game-service of the lane hosting a running candidate case, so that case fails with the code
     * the workbench records, and returns that case once it has failed.
     */
    private JsonNode failOneRunningCandidateCase(String runId) {
        var control = issuer.token("workbench-operator", Issuer.CONTROL);
        var victim = new JsonNode[1];
        await("a candidate case to be hosted by a candidate lane")
                .atMost(FIXTURE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(3))
                .until(() -> Workbench.orFalse(() -> {
                    for (var running : designer.caseSummaries(runId, "RUNNING", Manifests.CANDIDATE)) {
                        for (var lane : deployment.lanes(Manifests.CANDIDATE)) {
                            var checkpoint = Http.get(lane.gameUrl() + "/internal/simulation/v1/checkpoint", control);
                            if (checkpoint.status() == 200
                                    && running.path("caseKey")
                                            .asString()
                                            .equals(checkpoint
                                                    .json()
                                                    .path("caseKey")
                                                    .asString())
                                    && "ACTIVE"
                                            .equals(checkpoint
                                                    .json()
                                                    .path("state")
                                                    .asString())) {
                                Docker.stopService(lane.project(), "game-service");
                                victim[0] = running;
                                return true;
                            }
                        }
                    }
                    return false;
                }));
        var caseId = victim[0].path("caseId").asString();
        await("the interrupted lane's case " + caseId + " to fail")
                .atMost(Duration.ofMinutes(30))
                .pollInterval(Duration.ofSeconds(5))
                .until(() -> Workbench.orFalse(() -> "FAILED"
                        .equals(designer.caseDetail(runId, caseId).path("state").asString())));
        var detail = designer.caseDetail(runId, caseId);
        var attempts = detail.path("attempts");
        var failure = attempts.path(attempts.size() - 1).path("failure");
        assertThat(failure.path("code").asString()).isNotBlank();
        evidence.record(
                "injectedFault",
                Map.of(
                        "caseKey", detail.path("caseKey").asString(),
                        "failureCode", failure.path("code").asString(),
                        "failureMessage", failure.path("message").asString()));
        return detail;
    }

    private void assertTheComparisonShowsTheFailureAndOmitsItFromThePairedEstimate() {
        var request = Http.JSON.createObjectNode();
        request.set("baseline", side(multiRunId, Manifests.BASELINE));
        request.set("candidate", side(multiRunId, Manifests.CANDIDATE));
        var created = designer.post("/api/v1/comparisons", request, UUID.randomUUID());
        assertThat(created.status()).isBetween(200, 201);
        var comparisonId = created.json().path("comparisonId").asString();
        var comparison = designer.get("/api/v1/comparisons/" + comparisonId).requireStatus(200);
        var body = comparison.json();

        assertThat(designer.get("/api/v1/comparisons/" + comparisonId).body()).isEqualTo(comparison.body());
        assertThat(body.path("complete").asBoolean()).isFalse();
        assertThat(body.path("failedCounterparts").asInt()).isGreaterThanOrEqualTo(1);
        var declared = new ArrayList<String>();
        body.path("declaredDifferences")
                .forEach(difference -> declared.add(difference.path("field").asString()));
        assertThat(declared).contains("RULES");

        var failedId = failedCase.path("caseId").asString();
        JsonNode excluded = null;
        for (var pair : body.path("excludedPairs")) {
            if (failedId.equals(pair.path("candidateCaseId").asString(null))) {
                excluded = pair;
            }
        }
        assertThat(excluded).as("the failed case's pair is listed, not hidden").isNotNull();
        assertThat(excluded.path("reason").asString()).isEqualTo("FAILED_COUNTERPART");
        assertThat(excluded.path("candidateState").asString()).isEqualTo("FAILED");
        evidence.record("excludedPair", excluded);

        var failedRow = CaseRow.of(failedCase);
        JsonNode cohort = null;
        for (var candidate : body.path("cohorts")) {
            if (candidate.path("policyId").asString().equals(failedRow.policyId())
                    && candidate.path("playerCount").asInt() == failedRow.playerCount()
                    && candidate.path("factionSet").isNull()
                    && candidate.path("seatIndex").isNull()) {
                cohort = candidate;
            }
        }
        assertThat(cohort).as("the pooled cohort of the failed pair").isNotNull();
        assertThat(cohort.path("excludedBlocks").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(cohort.path("matchedBlocks").asInt()
                        + cohort.path("excludedBlocks").asInt())
                .isEqualTo(MULTI_SEEDS.size());

        var excludedSeeds = new HashSet<String>();
        for (var pair : body.path("excludedPairs")) {
            if (pair.path("policyId").asString().equals(failedRow.policyId())
                    && pair.path("playerCount").asInt() == failedRow.playerCount()) {
                excludedSeeds.add(pair.path("seed").asString());
            }
        }
        var matchedRows = multiRows.stream()
                .filter(row -> !excludedSeeds.contains(row.seed()))
                .toList();
        var tallies = ReportMath.factionTallies(matchedRows);
        for (var metric : cohort.path("metricDifferences")) {
            if (!"faction_win_rate".equals(metric.path("name").asString())) {
                continue;
            }
            var faction = metric.path("dimensions").path("faction").asString(null);
            var baselineKey =
                    new ReportMath.Key(Manifests.BASELINE, failedRow.policyId(), failedRow.playerCount(), faction);
            var candidateKey =
                    new ReportMath.Key(Manifests.CANDIDATE, failedRow.policyId(), failedRow.playerCount(), faction);
            assertThat(metric.path("baselineValue").asDouble())
                    .as("baseline %s over matched blocks only", faction)
                    .isCloseTo(tallies.get(baselineKey).winRate(), within(1e-9));
            assertThat(metric.path("candidateValue").asDouble())
                    .as("candidate %s over matched blocks only", faction)
                    .isCloseTo(tallies.get(candidateKey).winRate(), within(1e-9));
        }
    }

    private static JsonNode side(String runId, String variant) {
        var side = Http.JSON.createObjectNode();
        side.put("runId", runId);
        side.put("variantLabel", variant);
        return side;
    }

    // ------------------------------------------------------------------------------------------------------
    // Restart, reproduction and tampering
    // ------------------------------------------------------------------------------------------------------

    @Test
    @Order(7)
    void aSavedCaseReproducesExactlyAfterARestartWhileOtherLanesAreBusy() {
        var saved = matrixRows.stream()
                .filter(row -> row.succeeded()
                        && row.playerCount() == 3
                        && row.variant().equals(Manifests.BASELINE))
                .findFirst()
                .orElseThrow();
        var before = designer.caseDetail(matrixRunId, saved.caseId());
        var countsBefore = designer.run(matrixRunId).path("counts");

        var busy = busyRun();
        designer.awaitRun(
                busy,
                run -> run.path("counts").path("running").asInt() > 0,
                FIXTURE_TIMEOUT,
                "unrelated lanes to be busy");
        restartWorkbench();
        var interrupted = designer.awaitRun(
                busy,
                run -> !"RUNNING".equals(run.path("state").asString()),
                Duration.ofMinutes(10),
                "the restart to interrupt the unrelated run");
        assertThat(interrupted.path("state").asString()).isEqualTo("INTERRUPTED");
        designer.post("/api/v1/runs/" + busy + "/resume", null, null).requireStatus(202);

        var key = UUID.randomUUID();
        designer.reproduce(matrixRunId, saved.caseId(), key).requireStatus(202);
        var started = Instant.now();
        var reproduction = designer.awaitReproduction(matrixRunId, saved.caseId(), key, FIXTURE_TIMEOUT);
        assertThat(reproduction.path("state").asString()).isEqualTo("MATCH");
        assertThat(reproduction.path("firstDivergence").isNull()).isTrue();
        evidence.record(
                "reproduction",
                Map.of(
                        "caseKey", before.path("caseKey").asString(),
                        "semanticDigest",
                                before.path("result").path("semanticDigest").asString(),
                        "state", reproduction.path("state").asString(),
                        "seconds", Duration.between(started, Instant.now()).toSeconds()));

        var after = designer.caseDetail(matrixRunId, saved.caseId());
        assertThat(after.path("result")).isEqualTo(before.path("result"));
        assertThat(after.path("attempts")).hasSameSizeAs(before.path("attempts"));
        assertThat(designer.run(matrixRunId).path("counts")).isEqualTo(countsBefore);
        designer.awaitRun(busy, Workbench::isFinal, FIXTURE_TIMEOUT, "the unrelated run to finish");
    }

    private String busyRun() {
        var spec = new Manifests.Spec(
                "unrelated run " + deployment.runId(),
                List.of(Manifests.BASELINE, Manifests.CANDIDATE),
                List.of("100", "101"),
                List.of(3),
                List.of(List.of("ERASERS", "PROPHETS", "WEAVERS")),
                List.of(Manifests.RANDOM),
                lanes,
                CASE_TIMEOUT_SECONDS);
        var experiment = designer.createExperiment(Manifests.build(deployment, policies, spec), UUID.randomUUID());
        return designer.startRun(experiment.path("experimentId").asString(), UUID.randomUUID())
                .path("runId")
                .asString();
    }

    private void restartWorkbench() {
        Docker.run("compose", "-p", deployment.stackProject(), "restart", "workbench");
        await("the workbench to answer again")
                .atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofSeconds(3))
                .until(() -> Http.get(deployment.workbenchUrl() + "/actuator/health", null)
                                .status()
                        == 200);
    }

    @Test
    @Order(8)
    void tamperedOrMissingPinnedInputsAreRefusedAndCreateNothing() {
        var saved =
                matrixRows.stream().filter(CaseRow::succeeded).skip(1).limit(2).toList();
        var transcriptTarget = saved.get(0);
        var manifestTarget = saved.get(1);
        var before = reproductionCount();

        Docker.workbenchSql(
                deployment.stackProject(),
                "UPDATE evidence_artifact SET content = convert_to('tampered', 'UTF8') WHERE digest = "
                        + "(SELECT transcript_artifact FROM case_evidence WHERE scope_id = '"
                        + transcriptTarget.caseId() + "')");
        assertThat(designer.reproduce(matrixRunId, transcriptTarget.caseId(), UUID.randomUUID())
                        .requireStatus(409)
                        .code())
                .isEqualTo("MANIFEST_MISMATCH");

        Docker.workbenchSql(
                deployment.stackProject(),
                "UPDATE case_evidence SET manifest_digest = '" + "f".repeat(64) + "' WHERE scope_id = '"
                        + manifestTarget.caseId() + "'");
        assertThat(designer.reproduce(matrixRunId, manifestTarget.caseId(), UUID.randomUUID())
                        .requireStatus(409)
                        .code())
                .isEqualTo("MANIFEST_MISMATCH");

        var cancelled = cancelledCase();
        assertThat(designer.reproduce(cancelled.get(0), cancelled.get(1), UUID.randomUUID())
                        .requireStatus(409)
                        .code())
                .as("a case with nothing saved has no pinned inputs")
                .isEqualTo("MANIFEST_MISMATCH");

        assertThat(reproductionCount())
                .as("a refused reproduction creates nothing")
                .isEqualTo(before);
    }

    private int reproductionCount() {
        return Integer.parseInt(Docker.workbenchSql(deployment.stackProject(), "SELECT count(*) FROM reproduction")
                .trim());
    }

    /** A run cancelled straight after it starts, and one of its cancelled cases: [runId, caseId]. */
    private List<String> cancelledCase() {
        var spec = new Manifests.Spec(
                "cancelled run " + deployment.runId(),
                List.of(Manifests.BASELINE),
                List.of("200", "201", "202", "203", "204", "205", "206", "207"),
                List.of(3),
                List.of(List.of("ERASERS", "PROPHETS", "WEAVERS")),
                List.of(Manifests.RANDOM),
                lanes,
                CASE_TIMEOUT_SECONDS);
        var experiment = designer.createExperiment(Manifests.build(deployment, policies, spec), UUID.randomUUID());
        var runId = designer.startRun(experiment.path("experimentId").asString(), UUID.randomUUID())
                .path("runId")
                .asString();
        designer.post("/api/v1/runs/" + runId + "/cancel", null, null);
        designer.awaitRun(
                runId,
                run -> "CANCELLED".equals(run.path("state").asString()),
                FIXTURE_TIMEOUT,
                "the run to be cancelled");

        var cancelledCases = designer.caseSummaries(runId, "CANCELLED", null);
        assertThat(cancelledCases).as("cancellation keeps its cases visible").isNotEmpty();
        var finalRun = designer.run(runId);
        assertThat(finalRun.path("counts").path("cancelled").asInt()).isEqualTo(cancelledCases.size());
        assertThat(finalRun.path("counts").path("succeeded").asInt()
                        + finalRun.path("counts").path("failed").asInt()
                        + finalRun.path("counts").path("cancelled").asInt())
                .isEqualTo(finalRun.path("counts").path("requested").asInt());
        return List.of(runId, cancelledCases.getFirst().path("caseId").asString());
    }

    // ------------------------------------------------------------------------------------------------------
    // Repetition and redelivery
    // ------------------------------------------------------------------------------------------------------

    @Test
    @Order(9)
    void repeatedHttpOperationsNeverDuplicateExperimentsRunsOrSamples() {
        var manifest = Manifests.build(deployment, policies, matrixSpec());
        var experiments = designer.get("/api/v1/experiments?limit=1")
                .requireStatus(200)
                .json()
                .path("total")
                .asInt();
        var runs = designer.get("/api/v1/runs?limit=1")
                .requireStatus(200)
                .json()
                .path("total")
                .asInt();
        var countsBefore = designer.run(matrixRunId).path("counts");

        var key = UUID.randomUUID();
        var first =
                designer.createExperiment(manifest, key).path("experimentId").asString();
        var changed = manifest.deepCopy();
        changed.put("name", manifest.path("name").asString() + " changed");
        assertThat(designer.post("/api/v1/experiments", changed, key)
                        .requireStatus(409)
                        .code())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(designer.createExperiment(manifest, key).path("experimentId").asString())
                .isEqualTo(first);

        var runKey = UUID.randomUUID();
        var run = designer.startRun(first, runKey).path("runId").asString();
        assertThat(designer.startRun(first, runKey).path("runId").asString()).isEqualTo(run);
        designer.post("/api/v1/runs/" + run + "/cancel", null, null);
        designer.post("/api/v1/runs/" + run + "/cancel", null, null);
        designer.awaitRun(run, Workbench::isFinal, FIXTURE_TIMEOUT, "the repeated run to be cancelled");

        assertThat(designer.get("/api/v1/experiments?limit=1")
                        .json()
                        .path("total")
                        .asInt())
                .isEqualTo(experiments + 1);
        assertThat(designer.get("/api/v1/runs?limit=1").json().path("total").asInt())
                .isEqualTo(runs + 1);
        assertThat(designer.post("/api/v1/runs/" + matrixRunId + "/cancel", null, null)
                        .status())
                .as("cancelling a final run is accepted without reopening it")
                .isEqualTo(200);
        assertThat(designer.post("/api/v1/runs/" + matrixRunId + "/resume", null, null)
                        .requireStatus(409)
                        .code())
                .isEqualTo("INVALID_RUN_STATE");
        assertThat(designer.run(matrixRunId).path("counts")).isEqualTo(countsBefore);
    }

    @Test
    @Order(10)
    void redeliveredEventRecordsDoNotChangeAGameOrDuplicateEvidence() throws Exception {
        var spec = new Manifests.Spec(
                "redelivery " + deployment.runId(),
                List.of(Manifests.BASELINE),
                List.of("300"),
                List.of(3),
                List.of(List.of("ERASERS", "PROPHETS", "WEAVERS")),
                List.of(Manifests.RANDOM),
                lanes,
                CASE_TIMEOUT_SECONDS);
        var experiment = designer.createExperiment(Manifests.build(deployment, policies, spec), UUID.randomUUID());

        var duplicators = new ArrayList<KafkaDuplicator>();
        deployment
                .lanes(Manifests.BASELINE)
                .forEach(lane -> duplicators.add(new KafkaDuplicator(lane.kafkaBootstrap())));
        String runId;
        try {
            runId = designer.startRun(experiment.path("experimentId").asString(), UUID.randomUUID())
                    .path("runId")
                    .asString();
            var run = designer.awaitRun(runId, Workbench::isFinal, FIXTURE_TIMEOUT, "the redelivery run to finish");
            assertThat(run.path("counts").path("succeeded").asInt())
                    .as("a duplicated delivery must not fail a game: %s", run)
                    .isEqualTo(run.path("counts").path("requested").asInt());
        } finally {
            for (var duplicator : duplicators) {
                duplicator.close();
            }
        }
        var redelivered =
                duplicators.stream().mapToInt(KafkaDuplicator::redelivered).sum();
        assertThat(redelivered).as("records were delivered a second time").isPositive();

        var summaries = designer.caseSummaries(runId, null, null);
        for (var summary : summaries) {
            var steps = designer.replay(runId, summary.path("caseId").asString(), "OBSERVER", null);
            var seen = new HashSet<String>();
            for (var step : steps) {
                if ("EVENT".equals(step.path("phase").asString())) {
                    var event = step.path("observations");
                    assertThat(seen.add(event.path("source").asString() + "/"
                                    + event.path("eventId").asString()))
                            .as("an event is one logical event however often it was delivered")
                            .isTrue();
                }
            }
        }
        var saved = summaries.getFirst();
        var key = UUID.randomUUID();
        designer.reproduce(runId, saved.path("caseId").asString(), key).requireStatus(202);
        assertThat(designer.awaitReproduction(runId, saved.path("caseId").asString(), key, FIXTURE_TIMEOUT)
                        .path("state")
                        .asString())
                .as("the undisturbed replay of the disturbed game")
                .isEqualTo("MATCH");
        evidence.record("redelivery", Map.of("recordsRedelivered", redelivered, "cases", summaries.size()));
    }

    private Manifests.Spec matrixSpec() {
        return new Manifests.Spec(
                "seed-42 matrix " + deployment.runId(),
                List.of(Manifests.BASELINE, Manifests.CANDIDATE),
                List.of("42"),
                List.of(3, 4, 5),
                Manifests.fullFactionSets(),
                List.of(Manifests.RANDOM, Manifests.GREEDY),
                lanes,
                CASE_TIMEOUT_SECONDS);
    }
}
