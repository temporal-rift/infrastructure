package io.github.temporalrift.systemtest.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class BrowserHarnessTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void pollingKeepsBrowserCallsOnTheirOwningThread() {
        var owningThread = Thread.currentThread();
        var calls = new AtomicInteger();
        BrowserGameScenario.waitUntil(
                () -> {
                    assertThat(Thread.currentThread()).isSameAs(owningThread);
                    return calls.incrementAndGet() == 2;
                },
                "two polls on the browser thread",
                Duration.ofSeconds(3));
        assertThat(calls).hasValue(2);
    }

    @Test
    void publicStartingWeightsAndRevealedTerminalFactionsAreAllowed() {
        var state = state();
        IsolationCheck.assertPublicStateIsFiltered(state);
        state.put("phase", "GAME_ENDED");
        ((ObjectNode) state.path("players").get(0)).put("faction", "WEAVERS");
        IsolationCheck.assertPublicStateIsFiltered(state);
    }

    @Test
    void liveRosterCannotRevealFactions() {
        var state = state();
        ((ObjectNode) state.path("players").get(0)).put("faction", "WEAVERS");
        assertThatThrownBy(() -> IsolationCheck.assertPublicStateIsFiltered(state))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void publicOutcomesCannotRevealLiveProbabilities() {
        var state = state();
        ((ObjectNode) state.path("activeEvents").get(0).path("outcomes").get(0)).put("probability", 70);
        assertThatThrownBy(() -> IsolationCheck.assertPublicStateIsFiltered(state))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void publicSummariesCannotRevealSpecificCardsOrTargets() {
        var state = state();
        ((ObjectNode) state.path("lastRoundSummary").path("actionSummaries").get(0)).put("cardType", "PUSH");
        assertThatThrownBy(() -> IsolationCheck.assertPublicStateIsFiltered(state))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void publicProgressCannotRevealAnOpponentDecision() {
        var state = state();
        ((ObjectNode) state.path("phaseContext").path("actionRoundProgress")).put("specialAction", "THREAD");
        assertThatThrownBy(() -> IsolationCheck.assertPublicStateIsFiltered(state))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void publicChainCannotAttributeItsOwner() {
        var state = state();
        ((ObjectNode) state.path("chain")).put("playerId", "opponent");
        assertThatThrownBy(() -> IsolationCheck.assertPublicStateIsFiltered(state))
                .isInstanceOf(AssertionError.class);
    }

    private static ObjectNode state() {
        return (ObjectNode) JSON.readTree("""
                {
                  "phase": "ACTION_ROUND_2",
                  "players": [{"playerId": "opponent", "faction": null, "score": 0}],
                  "activeEvents": [{"outcomes": [{"outcomeId": "outcome", "initialProbability": 30}]}],
                  "lastRoundSummary": {"actionSummaries": [{"playerId": "opponent",
                    "actionCategory": "PROBABILITY_SHIFTER", "actionFamily": "CARD", "skipped": false}]},
                  "phaseContext": {"actionRoundProgress": {
                    "submittedCount": 1, "totalPlayers": 3, "pendingPlayerIds": ["viewer"]}},
                  "chain": {"status": "ACTIVE", "length": 1}
                }
                """);
    }
}
