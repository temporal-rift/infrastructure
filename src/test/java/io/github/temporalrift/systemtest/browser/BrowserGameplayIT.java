package io.github.temporalrift.systemtest.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
            assertThat(player.screen().finalScoreCount())
                    .as("%s's browser shows every player's authoritative final score", player.name())
                    .isEqualTo(players.size());
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

        BrowserGameScenario.waitUntil(
                () -> players.stream()
                        .allMatch(player -> !player.screen().currentFaction().isBlank()),
                "every player sees their own faction");
        assertThat(players.stream()
                        .map(player -> player.screen().currentFaction())
                        .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS");

        var coverage = driveGameToResults(players, true);

        for (var player : players) {
            assertThat(player.screen().finalScoreCount())
                    .as("%s's browser shows every player's authoritative final score", player.name())
                    .isEqualTo(players.size());
        }
        assertThat(coverage.cardActors())
                .as("every faction owner submits an ordinary card action")
                .containsAll(players.stream().map(BrowserPlayer::name).toList());
        assertThat(coverage.specialActors())
                .as("every faction owner with an available special submits it")
                .containsAll(coverage.specialAvailableActors())
                .isNotEmpty();
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
            assertThat(player.screen().finalScoreCount()).isEqualTo(players.size());
        }
    }

    @Test
    void lostActionResponseStillRecoversWithoutDuplicateSpendOrDeadlock() throws Exception {
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

        // Drop the acknowledgement after the service has accepted it: fetch forwards the real
        // request (so the service records the action), then abort hides the response from the
        // browser. Only the first matching POST is dropped; later rounds resume normally.
        var dropArmed = new AtomicBoolean(true);
        var serviceAccepted = new AtomicBoolean(false);
        var serviceStatus = new AtomicInteger(-1);
        var accepted = new CountDownLatch(1);
        host.page().route("**/eras/*/rounds/*/actions", route -> {
            if (!"POST".equalsIgnoreCase(route.request().method()) || !dropArmed.getAndSet(false)) {
                route.resume();
                return;
            }
            try {
                var response = route.fetch();
                serviceStatus.set(response.status());
                if (response.status() >= 200 && response.status() < 300) {
                    serviceAccepted.set(true);
                }
            } catch (RuntimeException _) {
                // The fetch itself failed, so nothing was accepted; the abort below still
                // surfaces a network failure to the browser instead of hanging the submit.
            } finally {
                accepted.countDown();
            }
            route.abort();
        });

        host.screen().submitFirstAvailableAction(false);
        assertThat(accepted.await(90, TimeUnit.SECONDS))
                .as("the service receives the action before reload")
                .isTrue();
        assertThat(serviceAccepted)
                .as("the dropped acknowledgement belongs to a service-accepted action (status %s)", serviceStatus.get())
                .isTrue();
        // Intentionally no wait for hasSubmittedAction: the acknowledgement was withheld, so the
        // browser must recover the accepted decision from authoritative state after reload.
        host.page().unroute("**/eras/*/rounds/*/actions");
        host.reload();
        BrowserGameScenario.waitUntil(
                () -> host.screen().hasSubmittedAction() && !host.screen().hasOpenActionRound(),
                "host's reloaded browser reconciles the same accepted decision, not a fresh open round");
        assertThat(host.screen().submitFirstAvailableAction(false))
                .as("the accepted action cannot be submitted or spent a second time")
                .isEqualTo(GameScreen.ActionSubmission.NONE);

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
            assertThat(player.screen().finalScoreCount()).isEqualTo(players.size());
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
     * @return the players who submitted cards or specials and whose special became available
     */
    private ActionCoverage driveGameToResults(List<BrowserPlayer> players, boolean preferSpecial) {
        Set<String> cardActors = new HashSet<>();
        Set<String> specialActors = new HashSet<>();
        Set<String> specialAvailableActors = new HashSet<>();
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
                            if (screen.hasAvailableSpecial()) {
                                specialAvailableActors.add(player.name());
                            }
                            var submitted = screen.submitFirstAvailableAction(
                                    preferSpecial && !specialActors.contains(player.name()));
                            if (submitted == GameScreen.ActionSubmission.CARD) {
                                cardActors.add(player.name());
                            } else if (submitted == GameScreen.ActionSubmission.SPECIAL) {
                                specialActors.add(player.name());
                            }
                        } else if (screen.hasOpenParadoxChoice()) {
                            screen.submitFirstEligibleParadoxChoice();
                        } else if (screen.canRefreshResults()) {
                            screen.refreshResults();
                        }
                    }
                    return allDone;
                },
                "every player reaches authoritative terminal results",
                Duration.ofMinutes(8));
        return new ActionCoverage(cardActors, specialActors, specialAvailableActors);
    }

    private record ActionCoverage(
            Set<String> cardActors, Set<String> specialActors, Set<String> specialAvailableActors) {}

    private void assertTimingIsRecordedAsATestOverride() {
        var manifest = scenario.effectiveManifest();
        assertThat(manifest.path("timingPreset").asText())
                .as("the isolated deployment's manifest identifies this suite's accelerated timing as an override")
                .isEqualTo("test-override");
    }
}
