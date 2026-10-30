package io.github.temporalrift.systemtest.workbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.systemtest.workbench.ReportMath.CaseRow;

/** The harness's own arithmetic and builders, checked without any deployment. */
class WorkbenchHarnessTest {

    private static final String DESCRIPTOR =
            """
            {"runId":"r1","issuer":"http://localhost:19000/default","workbenchUrl":"http://localhost:18200",
             "workbenchProject":"stack","ordinary":{"project":"ord","gameUrl":"g","timelineUrl":"t"},
             "maxEras":2,
             "lanes":[
              {"id":"wbe2e-1","project":"p1","gameUrl":"g1","timelineUrl":"t1","readUrl":"r1",
               "kafkaBootstrap":"k1","rulesDigest":"%s","contentDigest":"%s",
               "bots":["00000000-0000-4000-8000-000000000001"]},
              {"id":"wbe2e-2","project":"p2","gameUrl":"g2","timelineUrl":"t2","readUrl":"r2",
               "kafkaBootstrap":"k2","rulesDigest":"%s","contentDigest":"%s",
               "bots":["00000000-0000-4000-8000-000000000002"]}]}
            """.formatted("a".repeat(64), "c".repeat(64), "a".repeat(64), "c".repeat(64));

    private static final String LANE_BUNDLES =
            """
            [{"rulesArtifact":{"digest":"%s"},"contentArtifact":{"digest":"%s","name":"base"},
              "effectiveRulesDigest":"%s","effectiveContentDigest":"%s",
              "services":{"gameService":{"imageDigest":"sha256:%s","sourceRevision":"abc1234"}},
              "contracts":{"session-api":"3.0.0"},"laneCount":2}]
            """.formatted("1".repeat(64), "2".repeat(64), "a".repeat(64), "c".repeat(64), "e".repeat(64));

    private static final Workbench.VariantRules PREVIEW =
            (rulesArtifact, version, overrides) -> Http.JSON.readTree("""
            {"effectiveRulesDigest":"%s","overrides":{"game.rules.win-score-threshold":22}}
            """.formatted("b".repeat(64)));

    @Test
    void theFullFactionSetsAreTenThreeFourAndOneFiveFactionCombinations() {
        var sets = Manifests.fullFactionSets();

        assertThat(sets).hasSize(16);
        assertThat(sets.stream().filter(set -> set.size() == 3)).hasSize(10);
        assertThat(sets.stream().filter(set -> set.size() == 4)).hasSize(5);
        assertThat(sets.stream().filter(set -> set.size() == 5)).hasSize(1);
        assertThat(sets).doesNotHaveDuplicates();
    }

    @Test
    void aManifestCitesTheBaselineAndDefinesTheCandidateByOverrides() {
        var deployment = Deployment.of(DESCRIPTOR);
        var catalog = Http.JSON.readTree("""
                [{"id":"random","version":"1.0.0","artifactDigest":"%s"}]
                """.formatted("d".repeat(64)));

        var manifest = Manifests.build(
                deployment,
                new Workbench.Catalog(catalog, Http.JSON.readTree(LANE_BUNDLES), PREVIEW),
                new Manifests.Spec(
                        "m",
                        List.of("baseline", "candidate"),
                        List.of("42"),
                        List.of(3),
                        List.of(List.of("ERASERS", "PROPHETS", "WEAVERS")),
                        List.of(Manifests.RANDOM),
                        2,
                        900));

        assertThat(manifest.at("/variants/0/effectiveRulesDigest").asString()).isEqualTo("a".repeat(64));
        assertThat(manifest.at("/variants/0/overrides").isMissingNode()).isTrue();
        assertThat(manifest.at("/variants/1/effectiveRulesDigest").asString()).isEqualTo("b".repeat(64));
        assertThat(manifest.at("/variants/1/rulesArtifact/digest").asString()).isEqualTo("1".repeat(64));
        assertThat(manifest.at("/variants/1/parameterSpaceVersion").asInt()).isEqualTo(1);
        assertThat(manifest.at("/variants/1/overrides/game.rules.win-score-threshold")
                        .asInt())
                .isEqualTo(22);
        assertThat(manifest.at("/variants/1/effectiveContentDigest").asString()).isEqualTo("c".repeat(64));
        assertThat(manifest.at("/variants/1/contentArtifact/name").asString()).isEqualTo("base");
        assertThat(manifest.at("/services/gameService/imageDigest").asString()).isEqualTo("sha256:" + "e".repeat(64));
        assertThat(manifest.at("/contracts/session-api").asString()).isEqualTo("3.0.0");
        assertThat(manifest.at("/policies/0/artifactDigest").asString()).isEqualTo("d".repeat(64));
        assertThat(manifest.path("seatRotationMode").asString()).isEqualTo("CYCLIC");
        assertThat(manifest.path("timingMode").asString()).isEqualTo("LOGICAL");
    }

    @Test
    void aManifestCannotCiteABundleTheWorkbenchDoesNotList() {
        var deployment = Deployment.of(DESCRIPTOR);
        var catalog = new Workbench.Catalog(Http.JSON.readTree("[]"), Http.JSON.createArrayNode(), PREVIEW);
        var spec =
                new Manifests.Spec("m", List.of("baseline"), List.of("42"), List.of(3), List.of(), List.of(), 1, 900);

        assertThatThrownBy(() -> Manifests.build(deployment, catalog, spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wbe2e-1");
    }

    @Test
    void everyLaneMustServeTheBaselineBundle() {
        var mixed = DESCRIPTOR.replaceFirst("\"rulesDigest\":\"a{64}\"", "\"rulesDigest\":\"" + "f".repeat(64) + "\"");
        var deployment = Deployment.of(mixed);

        assertThatThrownBy(deployment::baselineLane)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different bundles");
    }

    @Test
    void tenErasersGamesWithFourWinsOneSharedReportFortyAndTenPercent() {
        var rows = new ArrayList<CaseRow>();
        for (var game = 0; game < 10; game++) {
            var winners = switch (game) {
                case 0 -> List.of("ERASERS", "PROPHETS");
                case 1, 2, 3 -> List.of("ERASERS");
                default -> List.of("WEAVERS");
            };
            rows.add(row("c" + game, "baseline", String.valueOf(game), "SUCCEEDED", winners));
        }

        var tally = ReportMath.factionTallies(rows).get(new ReportMath.Key("baseline", Manifests.RANDOM, 3, "ERASERS"));

        assertThat(tally.games()).isEqualTo(10);
        assertThat(tally.wins()).isEqualTo(4);
        assertThat(tally.winRate()).isEqualTo(0.4);
        assertThat(tally.sharedWins()).isEqualTo(1);
        assertThat(tally.sharedWinRate()).isEqualTo(0.1);
    }

    @Test
    void unfinishedCasesAreNeverGamesAndBlocksAreDistinctSeeds() {
        var rows = List.of(
                row("a", "baseline", "1", "SUCCEEDED", List.of("ERASERS")),
                row("b", "baseline", "1", "SUCCEEDED", List.of("WEAVERS")),
                row("c", "baseline", "2", "FAILED", List.of()),
                row("d", "baseline", "3", "CANCELLED", List.of()));

        var tally = ReportMath.factionTallies(rows).get(new ReportMath.Key("baseline", Manifests.RANDOM, 3, "ERASERS"));

        assertThat(tally.games()).isEqualTo(2);
        assertThat(ReportMath.independentBlocks(rows, "baseline", Manifests.RANDOM, 3))
                .isEqualTo(1);
    }

    private static CaseRow row(String id, String variant, String seed, String state, List<String> winners) {
        return new CaseRow(
                id,
                variant,
                seed,
                3,
                Manifests.RANDOM,
                state,
                "SUCCEEDED".equals(state) ? "WIN_CONDITION_MET" : null,
                List.of("ERASERS", "PROPHETS", "WEAVERS"),
                winners,
                new TreeMap<>(Map.of(0, 10, 1, 8, 2, 6)));
    }
}
