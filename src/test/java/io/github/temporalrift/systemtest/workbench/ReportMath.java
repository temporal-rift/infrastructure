package io.github.temporalrift.systemtest.workbench;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;

/**
 * Recomputes report figures independently from authoritative case results, so an exported report is checked
 * against the games rather than against itself.
 */
final class ReportMath {

    private ReportMath() {}

    /** The facts of one case a report counts. */
    record CaseRow(
            String caseId,
            String variant,
            String seed,
            int playerCount,
            String policyId,
            String state,
            String endReason,
            List<String> factions,
            List<String> winners,
            List<Integer> scoreThresholdWinnerSeats,
            Map<Integer, Integer> scores) {

        static CaseRow of(JsonNode detail) {
            var factions = new ArrayList<String>();
            detail.path("assignments")
                    .forEach(seat -> factions.add(seat.path("faction").asString()));
            var result = detail.path("result");
            var winners = new ArrayList<String>();
            var scoreThresholdWinnerSeats = new ArrayList<Integer>();
            var scores = new TreeMap<Integer, Integer>();
            if (!result.isNull() && !result.isMissingNode()) {
                result.path("winners").forEach(winner -> {
                    winners.add(winner.path("faction").asString());
                    if ("SCORE_THRESHOLD".equals(winner.path("winType").asString())) {
                        scoreThresholdWinnerSeats.add(winner.path("seatIndex").asInt());
                    }
                });
                result.path("finalScores")
                        .forEach(score -> scores.put(
                                score.path("seatIndex").asInt(),
                                score.path("score").asInt()));
            }
            return new CaseRow(
                    detail.path("caseId").asString(),
                    detail.path("variantLabel").asString(),
                    detail.path("seed").asString(),
                    detail.path("playerCount").asInt(),
                    detail.path("assignments").path(0).path("policyId").asString(),
                    detail.path("state").asString(),
                    result.isNull() ? null : result.path("endReason").asString(),
                    List.copyOf(factions),
                    List.copyOf(winners),
                    List.copyOf(scoreThresholdWinnerSeats),
                    scores);
        }

        boolean succeeded() {
            return "SUCCEEDED".equals(state);
        }
    }

    /** Games a faction played in a pooled cohort, how many it won, and how many of those wins were shared. */
    record Tally(int games, int wins, int sharedWins) {

        double winRate() {
            return games == 0 ? 0 : (double) wins / games;
        }

        double sharedWinRate() {
            return games == 0 ? 0 : (double) sharedWins / games;
        }
    }

    /** Identifies a pooled cohort of one faction. */
    record Key(String variant, String policyId, int playerCount, String faction) implements Comparable<Key> {

        @Override
        public int compareTo(Key other) {
            return toString().compareTo(other.toString());
        }
    }

    /** Pooled per-faction tallies over the succeeded cases; a shared win counts for every winner. */
    static Map<Key, Tally> factionTallies(List<CaseRow> rows) {
        var games = new TreeMap<Key, int[]>();
        for (var row : rows) {
            if (!row.succeeded()) {
                continue;
            }
            for (var faction : row.factions()) {
                var tally = games.computeIfAbsent(
                        new Key(row.variant(), row.policyId(), row.playerCount(), faction), key -> new int[3]);
                tally[0]++;
                if (row.winners().contains(faction)) {
                    tally[1]++;
                    if (row.winners().size() >= 2) {
                        tally[2]++;
                    }
                }
            }
        }
        var result = new TreeMap<Key, Tally>();
        games.forEach((key, tally) -> result.put(key, new Tally(tally[0], tally[1], tally[2])));
        return result;
    }

    /** A figure as the workbench reports it: rounded half-even to 6 decimal places. */
    static double reported(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** The distinct seeds among a cohort's succeeded games: its independent blocks. */
    static int independentBlocks(List<CaseRow> rows, String variant, String policyId, int playerCount) {
        Set<String> seeds = new TreeSet<>();
        rows.stream()
                .filter(CaseRow::succeeded)
                .filter(row -> row.variant().equals(variant)
                        && row.policyId().equals(policyId)
                        && row.playerCount() == playerCount)
                .forEach(row -> seeds.add(row.seed()));
        return seeds.size();
    }

    /** The pooled cohort of a report (no faction set, no seat) for one variant, policy and player count. */
    static JsonNode pooledCohort(JsonNode report, String variant, String policyId, int playerCount) {
        for (var cohort : report.path("cohorts")) {
            if (cohort.path("variantLabel").asString().equals(variant)
                    && cohort.path("policyId").asString().equals(policyId)
                    && cohort.path("playerCount").asInt() == playerCount
                    && cohort.path("factionSet").isNull()
                    && cohort.path("seatIndex").isNull()) {
                return cohort;
            }
        }
        return null;
    }

    /** A named metric of a cohort restricted to a faction, or null. */
    static JsonNode factionMetric(JsonNode cohort, String name, String faction) {
        for (var metric : cohort.path("metrics")) {
            if (metric.path("name").asString().equals(name)
                    && faction.equals(metric.path("dimensions").path("faction").asString(null))) {
                return metric;
            }
        }
        return null;
    }
}
