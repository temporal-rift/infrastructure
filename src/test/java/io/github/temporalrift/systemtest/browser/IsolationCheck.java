package io.github.temporalrift.systemtest.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
            var states = player.network().capturedExchanges().stream()
                    .filter(exchange -> exchange.url().endsWith("/state") && exchange.status() == 200)
                    .map(exchange -> JSON.readTree(exchange.responseBody()))
                    .toList();
            assertThat(states)
                    .as("%s receives participant state", player.name())
                    .isNotEmpty();
            for (var state : states) {
                assertPublicStateIsFiltered(state);
            }
        }
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
