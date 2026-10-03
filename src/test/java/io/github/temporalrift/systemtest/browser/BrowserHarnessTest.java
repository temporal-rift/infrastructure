package io.github.temporalrift.systemtest.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
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
    void anotherViewersEarnedIntelIsRejectedForEveryKind() {
        for (var kind : List.of("PROBABILITY", "INFLUENCE", "HAND_CARD")) {
            var owner = intelState(
                    kind,
                    switch (kind) {
                        case "PROBABILITY" -> "SCAN";
                        case "INFLUENCE" -> "TRACE";
                        default -> "INTERCEPT";
                    });
            var ownCapture =
                    List.of(stateExchange(owner), acceptedInformationAction(1, 1, 202, "event", "opponent", kind));
            IsolationCheck.assertEarnedIntelIsScoped("owner", ownCapture);
            var other = owner.deepCopy();
            other.putArray("myHand");
            assertThatThrownBy(() -> IsolationCheck.assertEarnedIntelIsScoped("other", List.of(stateExchange(other))))
                    .as("a %s grant belongs only to its recipient", kind)
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void independentlyEarnedIdenticalIntelAndPublicStartingWeightsAreAllowed() {
        var state = intelState("PROBABILITY", "SCAN");
        // Equality with a public starting weight must not be mistaken for a cross-player leak.
        ((ObjectNode) state.path("myRevealedIntel").get(0))
                .putArray("outcomes")
                .addObject()
                .put("outcomeId", "outcome")
                .put("probability", 30);
        var capture = List.of(stateExchange(state), acceptedInformationAction(1, 1, 202, "event", "opponent"));
        IsolationCheck.assertEarnedIntelIsScoped("first viewer", capture);
        var second = state.deepCopy();
        ((ObjectNode) second.path("myHand").get(0)).put("cardInstanceId", "independent-card");
        var request = acceptedInformationAction(1, 1, 202, "event", "opponent");
        var independentRequest = new NetworkPayloadRecorder.Exchange(
                request.url(), request.requestBody().replace("owned-card", "independent-card"), 202, "{}");
        IsolationCheck.assertEarnedIntelIsScoped("second viewer", List.of(stateExchange(second), independentRequest));
        second.putArray("myRevealedIntel");
        IsolationCheck.assertEarnedIntelIsScoped("public-only viewer", List.of(stateExchange(second)));
    }

    @Test
    void scanRefreshRemainsEntitledAfterTheCardLeavesTheHand() {
        var before = intelState("PROBABILITY", "SCAN");
        before.putArray("myRevealedIntel");
        var after = intelState("PROBABILITY", "SCAN");
        after.putArray("myHand");
        ((ObjectNode) after.path("myRevealedIntel").get(0)).put("observedInRound", 3);
        IsolationCheck.assertEarnedIntelIsScoped(
                "viewer",
                List.of(
                        stateExchange(before),
                        acceptedInformationAction(1, 1, 202, "event", "opponent"),
                        stateExchange(after)));
    }

    @Test
    void rejectedActionsAndOtherCardTypesCannotGrantIntel() {
        var scan = intelState("PROBABILITY", "SCAN");
        assertThatThrownBy(() -> IsolationCheck.assertEarnedIntelIsScoped(
                        "viewer",
                        List.of(stateExchange(scan), acceptedInformationAction(1, 1, 422, "event", "opponent"))))
                .isInstanceOf(AssertionError.class);
        var push = intelState("PROBABILITY", "PUSH");
        assertThatThrownBy(() -> IsolationCheck.assertEarnedIntelIsScoped(
                        "viewer",
                        List.of(stateExchange(push), acceptedInformationAction(1, 1, 202, "event", "opponent"))))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void intelRequiresTheCorrectGameEraRoundAndTarget() {
        for (var kind : List.of("PROBABILITY", "INFLUENCE", "HAND_CARD")) {
            var state = intelState(
                    kind,
                    switch (kind) {
                        case "PROBABILITY" -> "SCAN";
                        case "INFLUENCE" -> "TRACE";
                        default -> "INTERCEPT";
                    });
            for (var wrongGrant : List.of(
                    acceptedInformationAction(2, 1, 202, "event", "opponent", kind),
                    acceptedInformationAction(1, 2, 202, "event", "opponent", kind),
                    acceptedInformationAction(1, 1, 202, "different-event", "different-player", kind))) {
                assertThatThrownBy(() -> IsolationCheck.assertEarnedIntelIsScoped(
                                "viewer", List.of(stateExchange(state), wrongGrant)))
                        .isInstanceOf(AssertionError.class);
            }
            state.put("gameId", "different-game");
            assertThatThrownBy(() -> IsolationCheck.assertEarnedIntelIsScoped(
                            "viewer",
                            List.of(
                                    stateExchange(state),
                                    acceptedInformationAction(1, 1, 202, "event", "opponent", kind))))
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    void traceGradeTwoAllowsItsBroaderEventCoverage() {
        var state = intelState("INFLUENCE", "TRACE");
        ((ObjectNode) state.path("myHand").get(0)).put("grade", "II");
        IsolationCheck.assertEarnedIntelIsScoped(
                "viewer",
                List.of(
                        stateExchange(state),
                        acceptedInformationAction(1, 1, 202, "another-event", "opponent", "INFLUENCE")));
    }

    @Test
    void earnedIntelExpiresAtEraAndGameEnd() {
        for (var phase : List.of("ERA_END", "GAME_ENDED")) {
            var state = intelState("PROBABILITY", "SCAN");
            state.put("phase", phase);
            assertThatThrownBy(() -> IsolationCheck.assertEarnedIntelIsScoped(
                            "viewer",
                            List.of(stateExchange(state), acceptedInformationAction(1, 1, 202, "event", "opponent"))))
                    .isInstanceOf(AssertionError.class);
            state.putArray("myRevealedIntel");
            IsolationCheck.assertEarnedIntelIsScoped("viewer", List.of(stateExchange(state)));
        }
    }

    private static ObjectNode intelState(String kind, String cardType) {
        var state = state();
        state.put("gameId", "game").put("eraNumber", 1);
        state.putArray("myHand")
                .addObject()
                .put("cardInstanceId", "owned-card")
                .put("cardType", cardType)
                .put("grade", "I");
        state.putArray("myRevealedIntel")
                .addObject()
                .put("kind", kind)
                .put("eventId", "event")
                .put("targetPlayerId", "opponent")
                .put("observedInRound", 1);
        return state;
    }

    private static NetworkPayloadRecorder.Exchange stateExchange(ObjectNode state) {
        return new NetworkPayloadRecorder.Exchange(
                "http://fixture/api/v1/games/game/state", null, 200, JSON.writeValueAsString(state));
    }

    private static NetworkPayloadRecorder.Exchange acceptedInformationAction(
            int era, int round, int status, String eventId, String playerId) {
        return acceptedInformationAction(era, round, status, eventId, playerId, "PROBABILITY");
    }

    private static NetworkPayloadRecorder.Exchange acceptedInformationAction(
            int era, int round, int status, String eventId, String playerId, String kind) {
        var body = JSON.createObjectNode().put("actionType", "CARD").put("cardInstanceId", "owned-card");
        switch (kind) {
            case "PROBABILITY" -> body.putArray("targetEventIds").add(eventId);
            case "INFLUENCE" -> body.put("targetEventId", eventId);
            default -> body.put("targetPlayerId", playerId);
        }
        return new NetworkPayloadRecorder.Exchange(
                "http://fixture/api/v1/games/game/eras/%d/rounds/%d/actions".formatted(era, round),
                JSON.writeValueAsString(body),
                status,
                "{}");
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
