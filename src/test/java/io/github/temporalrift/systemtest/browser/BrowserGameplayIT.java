package io.github.temporalrift.systemtest.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Deployed-browser evidence (infrastructure#47) that a complete normal game is playable end to end
 * through the real client, with isolated separately authenticated player sessions, every faction's
 * normal path represented, and safe recovery from reload/timeout — complementing
 * {@code TemporalRiftSystemIT}'s command-only cross-service proof with genuine browser interaction.
 * Runs only under the {@code browser-e2e} Maven profile, against the isolated deployment its
 * lifecycle brings up (see pom.xml and {@code compose.browser-e2e.yml}).
 */
class BrowserGameplayIT {

    private BrowserGameScenario scenario;

    @AfterEach
    void closeBrowser() {
        if (scenario != null) {
            scenario.close();
        }
    }

    @Test
    void threePlayersCompleteANormalGameThroughTheBrowser() {
        scenario = new BrowserGameScenario();
        var players = signInAndStartLobby("Host", "Second", "Third");

        driveGameToResults(players, false);

        for (var player : players) {
            assertThat(player.screen().winnerNames())
                    .as("%s's browser shows authoritative winners", player.name())
                    .isNotEmpty();
        }
        IsolationCheck.assertHandsStayPrivate(players);
        IsolationCheck.assertEarnedKnowledgeStaysPrivate(players);
        assertTimingIsRecordedAsATestOverride();
    }

    @Test
    void everyFactionActsThroughTheBrowserInAFivePlayerGame() {
        scenario = new BrowserGameScenario();
        var players = signInAndStartLobby(
                "Erasers seat", "Prophets seat", "Revisionists seat", "Weavers seat", "Activists seat");

        // The live client has no real faction display to read (only game-client's always-rendered
        // sample fixture shows a faction, which is not this game's actual state — see
        // AppShell/sampleFixturePlayerView), so this cannot assert five distinct factions or map a
        // used special back to its owning faction. What is verifiable end to end: every seat reaches
        // authoritative results, and at least one faction special is exercised through the browser
        // somewhere in the game — weaker than "every faction's special", but concrete evidence
        // rather than a vacuously passing loop.
        var usedAnySpecial = driveGameToResults(players, true);

        for (var player : players) {
            assertThat(player.screen().winnerNames())
                    .as("%s's browser shows authoritative winners", player.name())
                    .isNotEmpty();
        }
        assertThat(usedAnySpecial)
                .as("at least one faction special is submitted through the browser during the game")
                .isTrue();
        IsolationCheck.assertHandsStayPrivate(players);
        IsolationCheck.assertEarnedKnowledgeStaysPrivate(players);
    }

    @Test
    void reloadAndRoundTimeoutRecoverWithoutDuplicateSpendOrDeadlock() {
        scenario = new BrowserGameScenario();
        var players = signInAndStartLobby("Host", "Second", "Third");
        var host = players.get(0);
        var slowPlayer = players.get(2);

        BrowserGameScenario.waitUntil(
                () -> players.stream()
                        .allMatch(p ->
                                p.screen().isHandKeepOffered() || p.screen().hasOpenActionRound()),
                "round 1 is reachable for every player");
        keepHandIfOffered(players);
        BrowserGameScenario.waitUntil(
                () -> players.stream().allMatch(p -> p.screen().hasOpenActionRound()),
                "round 1's action step is open for every player");

        // Host submits, then reloads: the accepted decision must survive the reload without
        // allowing (or needing) a second submission.
        host.screen().submitFirstAvailableAction(false);
        BrowserGameScenario.waitUntil(host.screen()::hasSubmittedAction, "host's action is accepted before reload");
        host.reload();
        BrowserGameScenario.waitUntil(
                () -> host.screen().hasSubmittedAction() && !host.screen().hasOpenActionRound(),
                "host's reloaded browser shows the same accepted decision, not a fresh open round");

        // The second player submits normally; the third deliberately never submits this round, so
        // the round must close on its own accelerated (test-override) timeout, not deadlock.
        players.get(1).screen().submitFirstAvailableAction(false);
        BrowserGameScenario.waitUntil(
                players.get(1).screen()::hasSubmittedAction, "second player's action is accepted");

        var round1Label = slowPlayer.screen().currentRoundLabel();
        BrowserGameScenario.waitUntil(
                () -> !slowPlayer.screen().currentRoundLabel().equals(round1Label)
                        || slowPlayer.screen().hasCompleteResults(),
                "the round closes on timeout for the player who never submitted, and the game progresses");

        // Finish the game normally so the harness leaves a clean, complete game behind.
        driveGameToResults(players, false);
        for (var player : players) {
            assertThat(player.screen().winnerNames()).isNotEmpty();
        }
    }

    private List<BrowserPlayer> signInAndStartLobby(String... playerNames) {
        var players = new ArrayList<BrowserPlayer>();
        for (var name : playerNames) {
            players.add(
                    scenario.signIn(name, name.toLowerCase(Locale.ROOT).replace(' ', '-') + "-" + UUID.randomUUID()));
        }

        var host = players.get(0);
        host.screen().createGame(host.name());
        BrowserGameScenario.waitUntil(() -> !host.screen().lobbyId().isBlank(), "the lobby is created");
        var invitationReference = host.screen().lobbyId();

        for (int i = 1; i < players.size(); i++) {
            players.get(i).screen().joinGame(invitationReference, players.get(i).name());
        }
        BrowserGameScenario.waitUntil(
                () -> players.stream().allMatch(p -> p.screen().memberCount() == players.size()),
                "every context sees the full lobby membership");

        BrowserGameScenario.waitUntil(host.screen()::isReadyToStart, "the host's lobby is ready to start");
        host.screen().startGame();
        for (var player : players) {
            BrowserGameScenario.waitUntil(
                    player.screen()::hasGameStarted, "%s sees the game start".formatted(player.name()));
        }
        return players;
    }

    private void keepHandIfOffered(List<BrowserPlayer> players) {
        for (var player : players) {
            if (player.screen().isHandKeepOffered()) {
                player.screen().keepFirstFiveOfferedCards();
            }
        }
    }

    /**
     * Drives every player to authoritative terminal results, submitting whatever step is currently
     * open for each. Never re-submits a round already accepted: the polling window would otherwise
     * see the same "Confirm action" control between a click and the server's acknowledgement and
     * submit a second action for that round, hiding exactly the duplicate-spend defect
     * {@link #reloadAndRoundTimeoutRecoverWithoutDuplicateSpendOrDeadlock} exists to catch.
     *
     * @return true if any player's action round used a faction special at least once during the game
     */
    private boolean driveGameToResults(List<BrowserPlayer> players, boolean preferSpecial) {
        Map<String, Boolean> usedSpecial = new HashMap<>();
        BrowserGameScenario.waitUntil(
                () -> {
                    boolean allDone = true;
                    for (var player : players) {
                        var screen = player.screen();
                        if (screen.hasCompleteResults()) {
                            continue;
                        }
                        allDone = false;
                        if (screen.isHandKeepOffered()) {
                            screen.keepFirstFiveOfferedCards();
                        } else if (screen.hasOpenActionRound() && !screen.hasSubmittedAction()) {
                            if (screen.submitFirstAvailableAction(preferSpecial)) {
                                usedSpecial.put(player.name(), true);
                            }
                        } else if (screen.hasOpenParadoxChoice()) {
                            screen.submitFirstEligibleParadoxChoice();
                        } else if (screen.canRefreshResults()) {
                            screen.refreshResults();
                        }
                    }
                    return allDone;
                },
                "every player reaches authoritative terminal results");
        return usedSpecial.containsValue(true);
    }

    private void assertTimingIsRecordedAsATestOverride() {
        var manifest = scenario.effectiveManifest();
        assertThat(manifest.path("timingPreset").asText())
                .as("the isolated deployment's manifest identifies this suite's accelerated timing as an override")
                .isEqualTo("test-override");
    }
}
