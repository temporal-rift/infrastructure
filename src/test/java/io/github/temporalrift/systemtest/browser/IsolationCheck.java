package io.github.temporalrift.systemtest.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asserts private-view isolation the way the architecture notes insist it must be checked: against
 * the actual network payloads every context receives, not just what happens to be rendered — "hiding
 * a field visually does not secure it." Ground truth for each player's own hand is read from that
 * player's own rendered UI (the one place it is legitimately shown), then every other player's full
 * captured network traffic is searched for it.
 */
final class IsolationCheck {

    private IsolationCheck() {}

    static void assertHandsStayPrivate(List<BrowserPlayer> players) {
        Map<String, List<String>> ownHandByPlayer = new LinkedHashMap<>();
        for (var player : players) {
            ownHandByPlayer.put(player.name(), player.screen().handCardNames());
        }

        for (var owner : players) {
            var ownCards = ownHandByPlayer.get(owner.name());
            assertThat(ownCards)
                    .as("%s's own hand must be visible to itself", owner.name())
                    .isNotEmpty();

            for (var other : players) {
                if (other == owner) {
                    continue;
                }
                var otherTraffic = other.network().capturedExchanges();
                for (var exchange : otherTraffic) {
                    var body = exchange.responseBody();
                    if (body == null) {
                        continue;
                    }
                    for (var card : ownCards) {
                        assertThat(body)
                                .as(
                                        "%s's network traffic (%s) must never contain %s's private hand card '%s'",
                                        other.name(), exchange.url(), owner.name(), card)
                                .doesNotContain(card);
                    }
                }
            }
        }
    }
}
