package io.github.temporalrift.systemtest.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * A large run killed in the middle keeps every result it had, resumes without recounting, and can be cancelled
 * without losing them. Sized by system properties: the acceptance size is 1000 cases interrupted after 400
 * successes; a case count must be a multiple of 100 (two variants, 50 coordinates per seed and variant).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkbenchRecoveryIT {

    private static final int CASES = Integer.getInteger("workbenchE2e.recovery.cases", 1000);
    private static final int INTERRUPT_AFTER = Integer.getInteger("workbenchE2e.recovery.interruptAfter", 400);
    private static final int RESUME_PROGRESS = Integer.getInteger("workbenchE2e.recovery.resumeProgress", 10);
    private static final Duration TIMEOUT =
            Duration.ofMinutes(Integer.getInteger("workbenchE2e.recovery.timeoutMinutes", 150));

    private final Deployment deployment = Deployment.load();
    private final Issuer issuer = new Issuer(deployment.issuer());
    private final Workbench designer =
            new Workbench(deployment.workbenchUrl(), issuer.token("designer-api", Issuer.DESIGNER));
    private final Evidence evidence = new Evidence("recovery");

    private String experimentId;
    private String runId;
    private Instant started;
    private Map<String, JsonNode> retained;

    @AfterAll
    void writeEvidence() {
        evidence.write(deployment);
    }

    @Test
    @Order(1)
    void aLargeRunIsKilledAfterTheConfiguredNumberOfSuccesses() {
        assertThat(CASES % 100)
                .as("recovery case count must be a multiple of 100")
                .isZero();
        var seeds = new java.util.ArrayList<String>();
        for (var seed = 1; seed <= CASES / 100; seed++) {
            seeds.add(String.valueOf(seed));
        }
        var spec = new Manifests.Spec(
                "recovery " + deployment.runId(),
                List.of(Manifests.BASELINE, Manifests.CANDIDATE),
                seeds,
                List.of(3, 4),
                recoverySets(),
                List.of(Manifests.RANDOM),
                deployment.lanes().size(),
                Integer.getInteger("workbenchE2e.caseTimeoutSeconds", 900));
        var policies = designer.policies();
        var experiment = designer.createExperiment(Manifests.build(deployment, policies, spec), UUID.randomUUID());
        experimentId = experiment.path("experimentId").asString();
        var key = UUID.randomUUID();
        var run = designer.startRun(experimentId, key);
        runId = run.path("runId").asString();
        started = Instant.now();
        assertThat(run.path("counts").path("requested").asInt()).isEqualTo(CASES);
        assertThat(designer.startRun(experimentId, key).path("runId").asString())
                .as("repeating the launch returns the original run")
                .isEqualTo(runId);

        designer.awaitRun(
                runId,
                current -> current.path("counts").path("succeeded").asInt() >= INTERRUPT_AFTER,
                TIMEOUT,
                INTERRUPT_AFTER + " cases to succeed");
        Docker.killService(deployment.stackProject(), "workbench");
        evidence.put(
                "interruptedAfterSeconds",
                Duration.between(started, Instant.now()).toSeconds());
    }

    /** All 10 three-faction and 5 four-faction sets: 30 + 20 = 50 coordinates per seed and variant. */
    private static List<List<String>> recoverySets() {
        return Manifests.fullFactionSets().stream()
                .filter(set -> set.size() < 5)
                .toList();
    }

    @Test
    @Order(2)
    void aRestartInterruptsTheRunAndKeepsEverySuccess() {
        Docker.startService(deployment.stackProject(), "workbench");
        var interrupted = designer.awaitRun(
                runId,
                current -> "INTERRUPTED".equals(current.path("state").asString()),
                Duration.ofMinutes(15),
                "the restarted workbench to report the run as interrupted");
        var succeeded = interrupted.path("counts").path("succeeded").asInt();
        assertThat(succeeded).isGreaterThanOrEqualTo(INTERRUPT_AFTER);

        var kept = new LinkedHashMap<String, JsonNode>();
        for (var summary : designer.caseSummaries(runId, "SUCCEEDED", null)) {
            var id = summary.path("caseId").asString();
            kept.put(id, designer.caseDetail(runId, id));
        }
        assertThat(kept).hasSize(succeeded);
        retained = Map.copyOf(kept);
        evidence.put("retainedAtInterruption", succeeded);
        assertThat(interrupted.path("counts").path("running").asInt())
                .as("no case is left running by an interrupted run")
                .isZero();
    }

    @Test
    @Order(3)
    void resumingRunsOnlyUnfinishedCasesAndNeverRecountsFinishedOnes() {
        designer.post("/api/v1/runs/" + runId + "/resume", null, null).requireStatus(202);
        assertThat(designer.post("/api/v1/runs/" + runId + "/resume", null, null)
                        .status())
                .as("a run that is no longer interrupted cannot be resumed again")
                .isEqualTo(409);

        var target = retained.size() + RESUME_PROGRESS;
        var progressed = designer.awaitRun(
                runId,
                current -> current.path("counts").path("succeeded").asInt() >= target,
                TIMEOUT,
                "the resumed run to add " + RESUME_PROGRESS + " successes");
        assertCounts(progressed.path("counts"));
        assertRetainedUnchanged();
    }

    @Test
    @Order(4)
    void cancellingPreservesResultsAndRepeatingItChangesNothing() {
        designer.post("/api/v1/runs/" + runId + "/cancel", null, null).requireStatus(202);
        var cancelled = designer.awaitRun(
                runId,
                current -> "CANCELLED".equals(current.path("state").asString()),
                Duration.ofMinutes(30),
                "the run to be cancelled");
        var counts = cancelled.path("counts");
        assertCounts(counts);
        assertThat(counts.path("pending").asInt()).isZero();
        assertThat(counts.path("running").asInt()).isZero();
        assertThat(counts.path("cancelled").asInt()).isPositive();
        assertThat(counts.path("succeeded").asInt()).isGreaterThanOrEqualTo(retained.size());
        assertRetainedUnchanged();

        assertThat(designer.post("/api/v1/runs/" + runId + "/cancel", null, null)
                        .status())
                .isEqualTo(200);
        assertThat(designer.run(runId).path("counts")).isEqualTo(counts);

        var cancelledCase = designer.caseSummaries(runId, "CANCELLED", null).getFirst();
        assertThat(designer.reproduce(runId, cancelledCase.path("caseId").asString(), UUID.randomUUID())
                        .requireStatus(409)
                        .code())
                .as("a cancelled case has nothing saved to reproduce from")
                .isEqualTo("MANIFEST_MISMATCH");

        var seconds = Duration.between(started, Instant.now()).toSeconds();
        evidence.put("finalCounts", counts);
        evidence.put("runtimeSeconds", seconds);
        evidence.put(
                "throughputSuccessesPerHour",
                Math.round(counts.path("succeeded").asInt() * 3600.0 / seconds * 10) / 10.0);
    }

    private void assertCounts(JsonNode counts) {
        var total = counts.path("succeeded").asInt()
                + counts.path("failed").asInt()
                + counts.path("cancelled").asInt()
                + counts.path("pending").asInt()
                + counts.path("running").asInt();
        assertThat(total)
                .as("every requested case is counted exactly once: %s", counts)
                .isEqualTo(CASES);
        assertThat(counts.path("succeeded").asInt()).isLessThanOrEqualTo(CASES);
    }

    private void assertRetainedUnchanged() {
        retained.forEach((id, before) -> {
            var after = designer.caseDetail(runId, id);
            assertThat(after.path("state").asString()).as("case %s", id).isEqualTo("SUCCEEDED");
            assertThat(after.path("result")).as("result of case %s", id).isEqualTo(before.path("result"));
            assertThat(after.path("attempts")).as("attempts of case %s", id).isEqualTo(before.path("attempts"));
        });
    }
}
