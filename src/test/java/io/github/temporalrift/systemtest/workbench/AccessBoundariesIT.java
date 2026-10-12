package io.github.temporalrift.systemtest.workbench;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Who may reach execution controls and evidence: ordinary deployments have no controls at all, lanes accept
 * only the operator, and the workbench refuses bot credentials. Also proves the cleanup is scoped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessBoundariesIT {

    private static final String DECOY = "wbe2e-protected-decoy";
    private static final String CONTROL_PATHS_PUT = "/internal/simulation/v1/execution";
    private static final String CHECKPOINT = "/internal/simulation/v1/checkpoint";
    private static final String CLOCK = "/internal/simulation/v1/clock";

    private final Deployment deployment = Deployment.load();
    private final Issuer issuer = new Issuer(deployment.issuer());
    private final Evidence evidence = new Evidence("access");

    @AfterAll
    void writeEvidence() {
        evidence.write(deployment);
    }

    @Test
    void anOrdinaryDeploymentHasNoExecutionControlsEvenForTheOperator() {
        var operator = issuer.token("workbench-operator", Issuer.CONTROL);
        var bot = issuer.token(deployment.lanes().getFirst().bots().getFirst().toString(), null);
        for (var base : new String[] {deployment.ordinaryGameUrl(), deployment.ordinaryTimelineUrl()}) {
            for (var token : new String[] {operator, bot}) {
                assertThat(Http.send("PUT", base + CONTROL_PATHS_PUT, token, "{}", Map.of())
                                .status())
                        .as("configure on %s", base)
                        .isEqualTo(404);
                assertThat(Http.get(base + CHECKPOINT, token).status())
                        .as("checkpoint on %s", base)
                        .isEqualTo(404);
                assertThat(Http.send("PUT", base + CLOCK, token, "{}", Map.of()).status())
                        .as("clock on %s", base)
                        .isEqualTo(404);
            }
        }
        evidence.put("ordinaryDeploymentControlStatus", 404);
    }

    @Test
    void aLaneAcceptsExecutionControlsFromTheOperatorAndNobodyElse() {
        var lane = deployment.lanes().getFirst();
        var operator = issuer.token("workbench-operator", Issuer.CONTROL);
        var bot = issuer.token(lane.bots().getFirst().toString(), null);
        var designer = issuer.token("designer-api", Issuer.DESIGNER);
        for (var base : new String[] {lane.gameUrl(), lane.timelineUrl()}) {
            assertThat(Http.get(base + CHECKPOINT, null).status())
                    .as("no credential")
                    .isEqualTo(401);
            assertThat(Http.get(base + CHECKPOINT, bot).status())
                    .as("bot credential")
                    .isEqualTo(403);
            assertThat(Http.get(base + CHECKPOINT, designer).status())
                    .as("designer credential")
                    .isEqualTo(403);
            assertThat(Http.send("PUT", base + CONTROL_PATHS_PUT, bot, "{}", Map.of())
                            .status())
                    .as("a bot cannot configure a lane")
                    .isEqualTo(403);
            assertThat(Http.get(base + CHECKPOINT, operator).status())
                    .as("operator on %s", base)
                    .isIn(200, 409);
        }
    }

    @Test
    void theWorkbenchRefusesBotsAndReadersWhatTheyAreNotEntitledTo() {
        var bot = issuer.token(deployment.lanes().getFirst().bots().getFirst().toString(), null);
        var botClient = new Workbench(deployment.workbenchUrl(), bot);
        var reader = new Workbench(deployment.workbenchUrl(), issuer.token("reader-api", Issuer.READER));
        var anonymous = new Workbench(deployment.workbenchUrl(), null);

        assertThat(anonymous.get("/api/v1/runs").status()).isEqualTo(401);
        assertThat(botClient.get("/api/v1/runs").status()).isEqualTo(403);
        assertThat(botClient.get("/api/v1/experiments").status()).isEqualTo(403);
        assertThat(reader.get("/api/v1/runs").status()).isEqualTo(200);
        assertThat(reader.post("/api/v1/experiment-previews", Http.JSON.createObjectNode(), null)
                        .status())
                .as("a reader may not write")
                .isEqualTo(403);
    }

    @Test
    void cleanupNamesOnlyTaskOwnedResourcesAndSparesAProtectedLookalike() throws Exception {
        Docker.run(
                "run",
                "-d",
                "--name",
                DECOY,
                "--label",
                "com.docker.compose.project=temporal-rift-sim-wbe2e-decoy",
                "--label",
                "com.docker.compose.config-files=/dev/null",
                "--label",
                "com.temporal-rift.owner=someone-else",
                "alpine:3",
                "sleep",
                "86400");

        var process = new ProcessBuilder("bash", "scripts/cleanup-workbench-e2e.sh", "--list")
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).isTrue();
        assertThat(process.exitValue()).as(output).isZero();

        deployment.lanes().forEach(lane -> assertThat(output).contains(lane.project()));
        assertThat(output)
                .contains(deployment.ordinaryProject())
                .contains(deployment.stackProject())
                .contains("temporal-rift-workbench-e2e-net")
                .doesNotContain("decoy");
        assertThat(Docker.containerExists("com.temporal-rift.owner=someone-else"))
                .isTrue();
    }
}
