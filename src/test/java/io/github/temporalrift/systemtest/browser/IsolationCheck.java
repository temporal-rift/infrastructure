package io.github.temporalrift.systemtest.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
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

    /**
     * Second, DOM-level isolation signal covering earned intel (future intelligence): each entry a
     * player's own "Your earned knowledge" panel renders (Scan/Trace/Intercept text, distinctive
     * enough — event titles, exact percentages, revealed player names — that unlike hand cards a
     * plain text match is not collision-prone) must never appear in another context's traffic.
     * Faction identity and unresolved-opponent-decision coverage are not implemented: game-client's
     * live (non-fixture) view has no real faction display to read from at all, and the exact wire
     * shape a normal-player payload would leak an unresolved decision through is not something this
     * suite can verify without a live stack to inspect — left as a known, documented gap rather than
     * a guessed-at check.
     */
    static void assertEarnedKnowledgeStaysPrivate(List<BrowserPlayer> players) {
        Map<String, List<String>> ownKnowledgeByPlayer = new LinkedHashMap<>();
        for (var player : players) {
            ownKnowledgeByPlayer.put(player.name(), player.screen().earnedKnowledgeEntries());
        }

        for (var owner : players) {
            for (var entry : ownKnowledgeByPlayer.get(owner.name())) {
                for (var other : players) {
                    if (other == owner) {
                        continue;
                    }
                    for (var exchange : other.network().capturedExchanges()) {
                        var body = exchange.responseBody();
                        if (body == null) {
                            continue;
                        }
                        assertThat(body)
                                .as(
                                        "%s's network traffic (%s) must never contain %s's earned knowledge '%s'",
                                        other.name(), exchange.url(), owner.name(), entry)
                                .doesNotContain(entry);
                    }
                }
            }
        }
    }
}
