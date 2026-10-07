package io.github.temporalrift.systemtest.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Asserts private-view isolation the way the architecture notes insist it must be checked: against
 * the actual network payloads every context receives, not just what happens to be rendered — "hiding
 * a field visually does not secure it."
 *
 * <p>Ground truth for each player's own hand is each exact {@code cardInstanceId} that ever appears
 * in that player's own captured traffic — read continuously throughout the session, not from a DOM
 * snapshot that could disappear once the game reaches terminal results — rather than rendered card
 * name/grade text: game-client can legitimately deal two different players a card sharing the same
 * displayed name and grade (different instances, same type), which a text-based check would either
 * miss or misreport. Every other player's full captured traffic is then searched for those exact
 * ids, which cannot collide across distinct card instances.
 */
final class IsolationCheck {

    private static final Pattern CARD_INSTANCE_ID =
            Pattern.compile("\"cardInstanceId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ACTION_PATH =
            Pattern.compile(".*/games/([^/]+)/eras/([0-9]+)/rounds/([0-9]+)/actions");

    private record IntelGrant(String gameId, int era, int round, JsonNode card, JsonNode targets) {
        boolean permits(JsonNode state, JsonNode intel) {
            if (!gameId.equals(state.path("gameId").asText())
                    || era != state.path("eraNumber").asInt()) {
                return false;
            }
            var observedRound = intel.path("observedInRound").asInt();
            var eventId = intel.path("eventId").asText();
            return switch (intel.path("kind").asText()) {
                case "PROBABILITY" ->
                    "SCAN".equals(card.path("cardType").asText())
                            && observedRound >= round
                            && targets.path("targetEventIds")
                                    .valueStream()
                                    .anyMatch(target -> eventId.equals(target.asText()));
                case "INFLUENCE" ->
                    "TRACE".equals(card.path("cardType").asText())
                            && observedRound == round
                            && ("II".equals(card.path("grade").asText())
                                    || eventId.equals(
                                            targets.path("targetEventId").asText()));
                case "HAND_CARD" ->
                    "INTERCEPT".equals(card.path("cardType").asText())
                            && observedRound == round
                            && targets.path("targetPlayerId")
                                    .asText()
                                    .equals(intel.path("targetPlayerId").asText());
                default -> false;
            };
        }
    }

    private IsolationCheck() {}

    static void assertHandsStayPrivate(List<BrowserPlayer> players) {
        Map<String, Set<String>> ownCardIdsByPlayer = new LinkedHashMap<>();
        for (var player : players) {
            ownCardIdsByPlayer.put(player.name(), cardInstanceIdsSeenBy(player));
        }

        for (var owner : players) {
            var ownIds = ownCardIdsByPlayer.get(owner.name());
            assertThat(ownIds)
                    .as("%s's own hand must be visible to itself", owner.name())
                    .isNotEmpty();

            for (var other : players) {
                if (other == owner) {
                    continue;
                }
                for (var exchange : other.network().capturedExchanges()) {
                    var body = withoutEarnedIntel(exchange.responseBody());
                    if (body == null) {
                        continue;
                    }
                    for (var cardId : ownIds) {
                        assertThat(body)
                                .as(
                                        "%s's network traffic (%s) must never contain %s's private card instance '%s'",
                                        other.name(), exchange.url(), owner.name(), cardId)
                                .doesNotContain(cardId);
                    }
                }
            }
        }
    }

    private static Set<String> cardInstanceIdsSeenBy(BrowserPlayer player) {
        Set<String> ids = new LinkedHashSet<>();
        for (var exchange : player.network().capturedExchanges()) {
            var body = withoutEarnedIntel(exchange.responseBody());
            if (body == null) {
                continue;
            }
            var matcher = CARD_INSTANCE_ID.matcher(body);
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }

    // Intercept legitimately puts an opponent's real card instances in the caller's own
    // myRevealedIntel; that is earned knowledge, not the caller's hand and not a leak.
    private static String withoutEarnedIntel(String body) {
        if (body == null) {
            return null;
        }
        try {
            if (JSON.readTree(body) instanceof ObjectNode state && state.has("myRevealedIntel")) {
                state.remove("myRevealedIntel");
                return JSON.writeValueAsString(state);
            }
        } catch (JacksonException _) {
            // Not a JSON document (HTML, scripts); searched as-is.
        }
        return body;
    }

    static void assertPublicViewsStayFiltered(List<BrowserPlayer> players) {
        for (var player : players) {
            var exchanges = player.network().capturedExchanges();
            var states = participantStates(exchanges);
            assertThat(states)
                    .as("%s receives participant state", player.name())
                    .isNotEmpty();
            for (var state : states) {
                assertPublicStateIsFiltered(state);
            }
            assertEarnedIntelIsScoped(player.name(), exchanges);
        }
    }

    /** Uses the recipient's accepted requests, so independently earned identical intel is allowed. */
    static void assertEarnedIntelIsScoped(String playerName, List<NetworkPayloadRecorder.Exchange> exchanges) {
        var states = participantStates(exchanges);
        Map<String, JsonNode> ownCards = new LinkedHashMap<>();
        for (var state : states) {
            for (var cards : List.of(
                    state.path("myHand"), state.path("pendingHandSelection").path("cards"))) {
                for (var card : cards) {
                    ownCards.put(card.path("cardInstanceId").asText(), card);
                }
            }
        }
        var grants = new ArrayList<IntelGrant>();
        // Collect the whole capture first: POST acknowledgements and projected state can arrive
        // in either order, and spent cards are absent from later hands.
        for (var exchange : exchanges) {
            var path = ACTION_PATH.matcher(exchange.url());
            if (!path.matches()
                    || exchange.status() < 200
                    || exchange.status() >= 300
                    || exchange.requestBody() == null) {
                continue;
            }
            var request = JSON.readTree(exchange.requestBody());
            var card = ownCards.get(request.path("cardInstanceId").asText());
            if ("CARD".equals(request.path("actionType").asText()) && card != null) {
                grants.add(new IntelGrant(
                        path.group(1),
                        Integer.parseInt(path.group(2)),
                        Integer.parseInt(path.group(3)),
                        card,
                        request));
            }
        }
        for (var state : states) {
            var intel = state.path("myRevealedIntel");
            assertThat(intel.isArray())
                    .as("%s receives a private intel array", playerName)
                    .isTrue();
            if (Set.of("ERA_END", "GAME_ENDED").contains(state.path("phase").asText())) {
                assertThat(intel.isEmpty())
                        .as("%s's intel expires at era end", playerName)
                        .isTrue();
            }
            for (var entry : intel) {
                assertThat(grants.stream().anyMatch(grant -> grant.permits(state, entry)))
                        .as(
                                "%s's %s intel in era %s requires its own accepted information card and target",
                                playerName,
                                entry.path("kind").asText(),
                                state.path("eraNumber").asInt())
                        .isTrue();
            }
        }
    }

    private static List<JsonNode> participantStates(List<NetworkPayloadRecorder.Exchange> exchanges) {
        return exchanges.stream()
                .filter(exchange -> exchange.url().endsWith("/state") && exchange.status() == 200)
                .map(NetworkPayloadRecorder.Exchange::responseBody)
                .filter(Objects::nonNull)
                .map(JSON::readTree)
                .toList();
    }

    static void assertPublicStateIsFiltered(JsonNode state) {
        for (var player : state.path("players")) {
            assertOnlyFields(player, "playerId", "playerName", "score", "isConnected", "faction");
            if (!"GAME_ENDED".equals(state.path("phase").asText())) {
                assertThat(player.path("faction").isMissingNode()
                                || player.path("faction").isNull())
                        .as("factions remain hidden before the terminal reveal")
                        .isTrue();
            }
        }
        for (var event : state.path("activeEvents")) {
            for (var outcome : event.path("outcomes")) {
                assertOnlyFields(outcome, "outcomeId", "description", "initialProbability");
            }
        }
        for (var summary : state.path("lastRoundSummary").path("actionSummaries")) {
            assertOnlyFields(summary, "playerId", "actionCategory", "actionFamily", "skipped");
        }
        for (var progress : List.of("actionRoundProgress", "paradoxResolutionProgress")) {
            var value = state.path("phaseContext").path(progress);
            if (value.isObject()) {
                assertOnlyFields(value, "submittedCount", "totalPlayers", "pendingPlayerIds");
            }
        }
        if (state.path("chain").isObject()) {
            assertOnlyFields(state.path("chain"), "status", "length");
        }
    }

    private static void assertOnlyFields(JsonNode value, String... allowed) {
        assertThat(value.propertyNames()).isSubsetOf(allowed);
    }
}
