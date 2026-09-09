package io.github.temporalrift.systemtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.systemtest.TemporalRiftScenario.ActiveEvent;
import io.github.temporalrift.systemtest.TemporalRiftScenario.Card;
import io.github.temporalrift.systemtest.TemporalRiftScenario.PlayerState;
import io.github.temporalrift.systemtest.TemporalRiftScenario.PlayerView;
import io.github.temporalrift.systemtest.TemporalRiftScenario.RevealedIntel;
import io.github.temporalrift.systemtest.TemporalRiftScenario.RoundState;
import io.github.temporalrift.systemtest.TemporalRiftScenario.ScoreBoard;

class TemporalRiftSystemIT {

    private static final URI GAME_HEALTH_URI = URI.create("http://localhost:18080/actuator/health");
    private static final URI VICTORIALOGS_TRACE_QUERY_URI =
            victoriaLogsQueryUri("* | unpack_json | traceId:* | spanId:* | limit 1");
    private static final List<String> CENTRALIZED_LOG_SERVICE_TAGS =
            List.of("game-service", "timeline-service", "read-service");

    // Player-targeting cards carry only targetPlayerId (no event/outcome fields) per action.yml's oneOf
    // constraint. JAM is both player-targeting and round-three-ineligible; isPlayableThisRound already
    // reflects that, so filtering on it handles the overlap without special-casing JAM here.
    private static final Set<String> PLAYER_TARGETING_CARD_TYPES =
            Set.of("NULLIFY", "REDIRECT", "AMPLIFY", "JAM", "INTERCEPT");
    private static final Set<String> TWO_OUTCOME_CARD_TYPES = Set.of("SWING", "COLLIDE");
    private static final Set<String> ROUND_ONE_INELIGIBLE_TYPES = Set.of("TRACE");
    private static final Set<String> ROUND_THREE_INELIGIBLE_TYPES = Set.of("JAM", "SCAN", "INTERCEPT");
    // `mySpecialActions` (which would give this per-player, live, without a faction lookup) is not yet
    // implemented in read-service — its own ProjectionRestMapper explicitly leaves it unset ("deferred to a
    // later slice"), and projection.yml documents the same. Until it's real, faction-to-special ownership has
    // no live equivalent to read instead, so it stays a hardcoded fact here, unlike round-eligibility (which
    // isPlayableThisRound already exposes live per-card). Only Weavers and Activists own no once-per-era-
    // budgeted special — with three distinct factions drawn from five for a three-player game, at least one
    // player is always assigned one of these three factions.
    private static final Map<String, String> BUDGETED_SPECIAL_BY_FACTION =
            Map.of("ERASERS", "ANNIHILATE", "PROPHETS", "SEAL", "REVISIONISTS", "MIMIC");

    // Matches compose.e2e.yml's game.rules.max-eras override: enough eras to reach a terminal state
    // (win, collapse, or stabilization) without playing out all five production eras of real combat.
    private static final int E2E_MAX_ERAS = 2;

    private final TemporalRiftScenario scenario = new TemporalRiftScenario();
    private final JsonHttpClient httpClient = new JsonHttpClient();

    @Test
    void victoriaLogsCentralizesServiceLogsAndExposesTraceContext() {
        httpClient.get(GAME_HEALTH_URI, null).assertStatus(200);

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(this::assertCentralizedLogMetadata);
    }

    @Test
    void securedApiRejectsMissingBearerToken() {
        scenario.createLobbyWithoutAuthentication("Anonymous").assertStatus(401);
    }

    @Test
    void lobbyLifecycleRejectsInvalidTransitionsAndTransfersHostBeforeClosing() {
        var originalHost = Actor.named("Original host");
        var transferredHost = Actor.named("Transferred host");
        var latePlayer = Actor.named("Late player");

        var created = scenario.as(originalHost).createLobby().assertStatus(201);
        var lobbyId = UUID.fromString(created.body().path("lobbyId").asText());
        assertThat(created.body().path("hostPlayerId").asText())
                .isEqualTo(originalHost.playerId().toString());
        assertThat(created.body().path("joinCode").asText()).isNotBlank();

        var joined = scenario.as(transferredHost).joinLobby(lobbyId).assertStatus(200);
        assertThat(joined.body().path("currentPlayers").size()).isEqualTo(2);

        scenario.as(transferredHost).joinLobby(lobbyId).assertStatus(409);
        scenario.as(originalHost).startGame(lobbyId).assertStatus(422);
        scenario.as(transferredHost).startGame(lobbyId).assertStatus(403);

        scenario.as(originalHost).leaveLobby(lobbyId).assertStatus(204);
        scenario.as(transferredHost).startGame(lobbyId).assertStatus(422);
        scenario.as(transferredHost).leaveLobby(lobbyId).assertStatus(204);
        scenario.as(latePlayer).joinLobby(lobbyId).assertStatus(404);
    }

    @Test
    void gameStartAssignsPrivateEraOneStateAndHidesItFromNonParticipants() {
        var host = Actor.named("Host");
        var playerTwo = Actor.named("Player two");
        var playerThree = Actor.named("Player three");
        var outsider = Actor.named("Outsider");
        var players = List.of(host, playerTwo, playerThree);

        var gameId = startGameWithThreePlayers(host, playerTwo, playerThree);
        dealAndSelectEraOneHands(gameId, players);

        awaitEraOneStatesAndRejectOutsider(gameId, players, outsider);
    }

    @Test
    void threeRoundCardPlayLifecycleTraversesResolutionScoringAndEraProjection() {
        var host = Actor.named("Host");
        var playerTwo = Actor.named("Player two");
        var playerThree = Actor.named("Player three");
        var outsider = Actor.named("Outsider");
        var players = List.of(host, playerTwo, playerThree);

        var gameId = startGameWithThreePlayers(host, playerTwo, playerThree);
        dealAndSelectEraOneHands(gameId, players);
        var eraOneStates = awaitEraOneStatesAndRejectOutsider(gameId, players, outsider);

        var budgetedEntry = eraOneStates.entrySet().stream()
                .filter(entry ->
                        BUDGETED_SPECIAL_BY_FACTION.containsKey(entry.getValue().myFaction()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected at least one player with a budgeted-special faction"));
        var budgetedPlayer = budgetedEntry.getKey();
        var budgetedSpecial =
                BUDGETED_SPECIAL_BY_FACTION.get(budgetedEntry.getValue().myFaction());

        // Both probes now have guaranteed material every run: `hand-deal-forced-types` (e2e-only config,
        // see compose.e2e.yml) forces TRACE (round-1-ineligible, not player-targeting) and NULLIFY
        // (player-targeting, not round-restricted) into every player's seven-card deal, and
        // `chooseEraOneHand` already keeps one of each when selecting the five-card hand. Neither probe can
        // be asserted on every single call, though — TRACE is only ineligible in round 1, and a probe
        // consumes no card, so which specific call ends up exercising each one still varies by player/round.
        // Tracking across the whole scenario and asserting both fired at least once keeps the guarantee
        // real without hardcoding which call site it happens on.
        var roundIneligibilityProbed = new boolean[] {false};
        var playerTargetingProbed = new boolean[] {false};

        playEraOneRoundOne(
                gameId,
                host,
                players,
                budgetedPlayer,
                budgetedSpecial,
                roundIneligibilityProbed,
                playerTargetingProbed);
        playEraOneRoundTwo(
                gameId,
                host,
                players,
                budgetedPlayer,
                budgetedSpecial,
                roundIneligibilityProbed,
                playerTargetingProbed);
        playEraOneRoundThree(gameId, host, players, roundIneligibilityProbed, playerTargetingProbed);

        assertThat(roundIneligibilityProbed[0])
                .as("the round-ineligibility rejection (422-12) was exercised at least once")
                .isTrue();
        assertThat(playerTargetingProbed[0])
                .as("the player-targeting counterplay was exercised at least once")
                .isTrue();

        verifyScoresAndLaterEraProjection(gameId, host, players, eraOneStates);
        verifySpecialBudgetResetsInEraTwo(gameId, budgetedPlayer, budgetedSpecial);
    }

    @Test
    void multiTargetScanDeliversPrivateLiveIntelWithSuppressionCleanupAndKafkaBoundarySafety() {
        var scanner = Actor.named("Scanner");
        var victimScanner = Actor.named("Victim scanner");
        var nullifier = Actor.named("Nullifier");
        var players = List.of(scanner, victimScanner, nullifier);

        var gameId = startGameWithThreePlayers(scanner, victimScanner, nullifier);
        dealAndSelectHand(gameId, players, 1, TemporalRiftSystemIT::chooseScanScenarioHand);

        var roundOneStates = players.stream()
                .collect(Collectors.toMap(player -> player, player -> awaitPlayerAtRound(player, gameId, 1)));

        // Forcing the SCAN *type* into every deal doesn't force its *grade* (WeightedCardDealer still rolls a
        // weighted grade per forced card), so which of the three players actually got grade II/III is a property
        // of this run, not something assignable up front -- compose.e2e.yml biases the roll toward II/III, but
        // this still fails loudly rather than silently degrading to a single-target grade-I run if the roll goes
        // the other way for everyone.
        var mainScannerEntry = roundOneStates.entrySet().stream()
                .filter(entry -> !"I".equals(scanCard(entry.getValue()).grade()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No player's forced SCAN resolved at grade II or III this run: "
                        + roundOneStates.values().stream()
                                .map(TemporalRiftSystemIT::scanCard)
                                .toList()));
        var mainScanner = mainScannerEntry.getKey();
        var mainScannerState = mainScannerEntry.getValue();
        var others =
                players.stream().filter(player -> !player.equals(mainScanner)).toList();
        var victim = others.get(0);
        var nullifyingPlayer = others.get(1);

        var mainScanCard = scanCard(mainScannerState);
        var targetCount = targetCountForGrade(mainScanCard.grade());
        var targetEvents =
                mainScannerState.activeEvents().stream().limit(targetCount).toList();
        var targetEventIds = targetEvents.stream().map(ActiveEvent::eventId).toList();

        scenario.as(mainScanner)
                .playCardTargetingEvents(gameId, 1, 1, mainScanCard, targetEventIds)
                .assertStatus(202);
        awaitRoundClosed(mainScanner, gameId, 1, 1, 1);

        var afterRoundOne = scenario.awaitPlayerState(
                mainScanner,
                gameId,
                candidate -> candidate.revealedIntel().size() == targetCount
                        && candidate.revealedIntel().stream().allMatch(entry -> entry.observedInRound() == 1),
                mainScanner.name() + " receives round-1 scan intel");
        assertThat(afterRoundOne.revealedIntel().stream()
                        .map(RevealedIntel::eventId)
                        .toList())
                .as("scanned intel covers exactly the requested events")
                .containsExactlyInAnyOrderElementsOf(targetEventIds);

        var nullifierBeforeRound2 = scenario.awaitPlayerState(
                nullifyingPlayer,
                gameId,
                candidate -> candidate.revealedIntel().isEmpty(),
                nullifyingPlayer.name() + " sees no scan intel");
        assertThat(nullifierBeforeRound2.revealedIntel())
                .as("a non-scanning player never sees exact values or the scanned selection")
                .isEmpty();

        // Kafka-boundary fault injection (design.md decision 5): replay one already-real round-1 reveal with a
        // duplicate envelope eventId. Storage is keyed by (game, viewer, era, event), so this proves the
        // client-observable contract -- no duplicate projected intel -- rather than the internal dedup path.
        var replayEnvelopeId = UUID.randomUUID();
        var replayTarget = targetEvents.getFirst();
        try (var injector = new KafkaFaultInjector()) {
            injector.publishProbabilityStateRevealed(
                    replayEnvelopeId,
                    gameId,
                    1,
                    1,
                    mainScanner.playerId(),
                    replayTarget.eventId(),
                    fabricatedOutcomes(replayTarget));
            injector.publishProbabilityStateRevealed(
                    replayEnvelopeId,
                    gameId,
                    1,
                    1,
                    mainScanner.playerId(),
                    replayTarget.eventId(),
                    fabricatedOutcomes(replayTarget));
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var current = scenario.as(mainScanner).getPlayerState(gameId).assertStatus(200);
            var intel = PlayerState.from(current.body()).revealedIntel();
            assertThat(intel.stream()
                            .filter(entry -> entry.eventId().equals(replayTarget.eventId()))
                            .count())
                    .as("replayed reveal delivery does not create a duplicate intel entry")
                    .isEqualTo(1);
        });

        // Constructed here, before Round 2's actions are even submitted, so its consumer group already has a
        // confirmed partition assignment before the real BandedProbabilityPublished for Round 2 is produced.
        var bandProbe = new KafkaEventProbe();

        // Round 2: the victim plays their own Scan, which the nullifier cancels in the same round. Neither
        // targets the main scanner's events, so this round doubles as the "no player action affects a scanned
        // event" case -- the main scanner's intel still refreshes purely from the round closing.
        var victimStateRound2 = awaitPlayerAtRound(victim, gameId, 2);
        var victimScanCard = scanCard(victimStateRound2);
        var victimTargetEventIds = victimStateRound2.activeEvents().stream()
                .limit(targetCountForGrade(victimScanCard.grade()))
                .map(ActiveEvent::eventId)
                .toList();
        scenario.as(victim)
                .playCardTargetingEvents(gameId, 1, 2, victimScanCard, victimTargetEventIds)
                .assertStatus(202);

        var nullifierStateRound2 = awaitPlayerAtRound(nullifyingPlayer, gameId, 2);
        var nullifyCard = nullifyCard(nullifierStateRound2);
        scenario.as(nullifyingPlayer)
                .playCardTargetingPlayer(gameId, 1, 2, nullifyCard, victim.playerId())
                .assertStatus(202);

        awaitRoundClosed(mainScanner, gameId, 1, 2, 2);

        var afterRoundTwo = scenario.awaitPlayerState(
                mainScanner,
                gameId,
                candidate -> candidate.revealedIntel().size() == targetCount
                        && candidate.revealedIntel().stream().allMatch(entry -> entry.observedInRound() == 2),
                mainScanner.name() + " receives round-2 refreshed scan intel");

        var bandCheckEvent = targetEvents.getFirst();
        var bandCheckOutcomeId = bandCheckEvent.outcomeIds().getFirst();
        var realBand = bandProbe
                .awaitBand(gameId, 1, bandCheckEvent.eventId(), bandCheckOutcomeId, Duration.ofSeconds(30))
                .orElseThrow(() ->
                        new AssertionError("BandedProbabilityPublished never observed for the scanned event/outcome"));
        bandProbe.close();
        var exactProbability =
                afterRoundTwo.probabilityIntelFor(bandCheckEvent.eventId()).orElseThrow().outcomes().stream()
                        .filter(outcome -> outcome.outcomeId().equals(bandCheckOutcomeId))
                        .findFirst()
                        .orElseThrow()
                        .probability();
        assertThat(bandFor(exactProbability))
                .as("the scanning player's exact value agrees with the real public band for the same state")
                .isEqualTo(realBand);

        var victimAfterRoundTwo = scenario.awaitPlayerState(
                victim, gameId, candidate -> candidate.eraNumber() == 1, victim.name() + " state after round 2");
        assertThat(victimAfterRoundTwo.revealedIntel())
                .as("a same-round Nullify on the Scan suppresses its reveal")
                .isEmpty();

        // Round 3: the victim stalls one of the main scanner's covered events. Only that event's intel should
        // stop advancing; the scanner's other covered event(s) keep refreshing.
        var victimStateRound3 = awaitPlayerAtRound(victim, gameId, 3);
        var stallCard = victimStateRound3.hand().stream()
                .filter(card -> "STALL".equals(card.cardType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Victim has no STALL card: " + victimStateRound3));
        var stalledEvent = targetEvents.get(1);
        scenario.as(victim)
                .playCard(gameId, 1, 3, stallCard, stalledEvent.eventId(), null, targetOutcome(stalledEvent))
                .assertStatus(202);
        awaitRoundClosed(mainScanner, gameId, 1, 3, 1);

        var afterRoundThree = scenario.awaitPlayerState(
                mainScanner,
                gameId,
                candidate -> targetEventIds.stream()
                        .filter(eventId -> !eventId.equals(stalledEvent.eventId()))
                        .allMatch(eventId -> candidate
                                .probabilityIntelFor(eventId)
                                .map(entry -> entry.observedInRound() == 3)
                                .orElse(false)),
                mainScanner.name() + " receives round-3 refreshed scan intel for non-stalled events");
        assertThat(afterRoundThree
                        .probabilityIntelFor(stalledEvent.eventId())
                        .orElseThrow()
                        .observedInRound())
                .as("the stalled event's intel stops advancing past the round it was stalled")
                .isEqualTo(2);

        // Reconnect: re-fetching state mid-era must return the same intel, and never leak it to another player.
        var reconnectState = scenario.awaitPlayerState(
                mainScanner,
                gameId,
                candidate -> candidate.revealedIntel().size() == targetEventIds.size(),
                mainScanner.name() + " reconnect re-fetch preserves intel");
        assertThat(reconnectState.probabilityIntelFor(stalledEvent.eventId())).isPresent();
        var nullifierReconnect = scenario.awaitPlayerState(
                nullifyingPlayer,
                gameId,
                candidate -> candidate.revealedIntel().isEmpty(),
                nullifyingPlayer.name() + " reconnect re-fetch still has no scan intel");
        assertThat(nullifierReconnect.revealedIntel()).isEmpty();

        var afterEra = awaitEraAdvancedOrGameEnded(mainScanner, gameId, 1);
        assertThat(afterEra.revealedIntel())
                .as("scan intel is cleared once era 1 ends (or the game ends directly)")
                .isEmpty();

        // Kafka-boundary fault injection: a stale prior-era reveal delivered after the era boundary must never
        // resurrect intel into the now-current era's state.
        var staleEvent = targetEvents.get(1);
        try (var injector = new KafkaFaultInjector()) {
            injector.publishProbabilityStateRevealed(
                    UUID.randomUUID(),
                    gameId,
                    1,
                    3,
                    mainScanner.playerId(),
                    staleEvent.eventId(),
                    fabricatedOutcomes(staleEvent));
        }
        await().pollDelay(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var current = scenario.as(mainScanner).getPlayerState(gameId).assertStatus(200);
            assertThat(PlayerState.from(current.body()).probabilityIntelFor(staleEvent.eventId()))
                    .as("a delayed prior-era reveal must not resurrect intel across the era boundary")
                    .isEmpty();
        });
    }

    private static Card scanCard(PlayerState state) {
        return state.hand().stream()
                .filter(card -> "SCAN".equals(card.cardType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Player has no SCAN card: " + state));
    }

    private static Card nullifyCard(PlayerState state) {
        return state.hand().stream()
                .filter(card -> "NULLIFY".equals(card.cardType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Player has no NULLIFY card: " + state));
    }

    private static int targetCountForGrade(String grade) {
        return switch (grade) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            default -> throw new IllegalArgumentException("Unknown SCAN grade: " + grade);
        };
    }

    private static String bandFor(int probability) {
        if (probability <= 30) {
            return "LOW";
        }
        if (probability <= 60) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    // Fabricates a plausible outcome payload for a synthetic fault-injected reveal -- values are arbitrary
    // (the fault-injection scenarios assert delivery/idempotency/era-scoping behavior, never these values).
    private static List<Map<String, Object>> fabricatedOutcomes(ActiveEvent event) {
        var outcomes = new ArrayList<Map<String, Object>>();
        for (var index = 0; index < event.outcomeIds().size(); index++) {
            outcomes.add(Map.of(
                    "outcomeId",
                    event.outcomeIds().get(index).toString(),
                    "probability",
                    index == 0 ? 70 : 15,
                    "isAnnihilated",
                    false,
                    "isSealed",
                    false));
        }
        return outcomes;
    }

    // Keeps SCAN, STALL, and NULLIFY -- all four forced types except TRACE are relevant to this scenario, and
    // every player's deal offers all four (hand-deal-forced-types in compose.e2e.yml) -- plus filler cards that
    // this scenario never plays, to reach the required five-card selection.
    private static List<UUID> chooseScanScenarioHand(List<Card> offer) {
        var kept = new ArrayList<Card>();
        for (var type : List.of("SCAN", "STALL", "NULLIFY")) {
            offer.stream()
                    .filter(card -> type.equals(card.cardType()))
                    .filter(card -> !kept.contains(card))
                    .findFirst()
                    .ifPresent(kept::add);
        }
        offer.stream()
                .filter(card -> !kept.contains(card))
                .limit(5 - kept.size())
                .forEach(kept::add);
        return kept.stream().map(Card::cardInstanceId).toList();
    }

    @Test
    void gameReachesGameEndedWithAgreeingFinalScoresAndRevealedFactions() {
        var host = Actor.named("Host");
        var playerTwo = Actor.named("Player two");
        var playerThree = Actor.named("Player three");
        var players = List.of(host, playerTwo, playerThree);

        var gameId = startGameWithThreePlayers(host, playerTwo, playerThree);
        dealAndSelectHand(gameId, players, 1, TemporalRiftSystemIT::chooseAlwaysPlayableHand);

        var finalStates = playUntilGameEnded(gameId, players);

        for (var player : players) {
            assertThat(finalStates.get(player).players())
                    .as("%s sees every player's revealed faction", player.name())
                    .allSatisfy(view -> assertThat(view.faction()).isNotBlank());
        }

        verifyFinalScoresAgreeAcrossServices(gameId, host, players, finalStates);

        var finalEraNumber = finalStates.get(host).eraNumber();
        var history = scenario.awaitGameHistory(
                host,
                gameId,
                candidate -> candidate.era(finalEraNumber).isPresent(),
                "game history is durable through the final era");
        for (var eraNumber = 1; eraNumber <= finalEraNumber; eraNumber++) {
            var finalEraNumberCopy = eraNumber;
            var era = history.era(eraNumber)
                    .orElseThrow(() -> new AssertionError("Missing history entry for era " + finalEraNumberCopy));
            assertThat(era.outcomes().size() + era.cascadedEvents().size())
                    .as("era %d history reflects resolved or cascaded outcomes", eraNumber)
                    .isGreaterThan(0);
        }
    }

    // Bounded by E2E_MAX_ERAS rather than looping forever: with game.rules.max-eras overridden to that
    // value (compose.e2e.yml), the game is guaranteed to reach GAME_ENDED (via win, collapse, or
    // stabilization) at or before that era's resolution, so exceeding it is a genuine defect worth failing
    // loudly on rather than silently retrying past this scenario's time budget.
    private Map<Actor, PlayerState> playUntilGameEnded(UUID gameId, List<Actor> players) {
        for (var eraNumber = 1; eraNumber <= E2E_MAX_ERAS; eraNumber++) {
            playEraToCompletion(gameId, players, eraNumber);

            var currentEra = eraNumber;
            var afterEra = players.stream()
                    .collect(Collectors.toMap(
                            player -> player, player -> awaitEraAdvancedOrGameEnded(player, gameId, currentEra)));
            if ("GAME_ENDED".equals(afterEra.get(players.getFirst()).phase())) {
                return afterEra;
            }

            for (var player : players) {
                var keptCardIds = chooseAlwaysPlayableHand(afterEra.get(player).pendingHand());
                scenario.as(player)
                        .selectHand(gameId, currentEra + 1, keptCardIds)
                        .assertStatus(202);
            }
        }
        throw new AssertionError("Game did not reach GAME_ENDED within " + E2E_MAX_ERAS + " configured eras");
    }

    // The game can end mid-era-loop (a score-threshold win or a timeline collapse) instead of only ever
    // advancing to the next era's deal (a timeline stabilization at the max-eras boundary) -- awaiting
    // either as one predicate is what makes this scenario robust to whichever terminal condition the
    // random card play actually triggers, rather than assuming era-by-era advancement is the only path.
    //
    // Unlike era one's deal (checked with hand().isEmpty() in dealAndSelectHand, since there's no prior
    // era's hand to linger), HandDealt only fills pendingHandSelection -- it never touches myHand, so a
    // later era's myHand still holds the previous era's five selected cards until this era's own
    // HandSelected resolves it. Requiring hand().isEmpty() here would wait forever for a state that never
    // occurs past era one.
    private PlayerState awaitEraAdvancedOrGameEnded(Actor player, UUID gameId, int currentEraNumber) {
        return scenario.awaitPlayerState(
                player,
                gameId,
                candidate -> "GAME_ENDED".equals(candidate.phase())
                        || (candidate.eraNumber() == currentEraNumber + 1
                                && candidate.pendingHand().size() == 7),
                player.name() + " reaches GAME_ENDED or the era-" + (currentEraNumber + 1) + " deal");
    }

    private void playEraToCompletion(UUID gameId, List<Actor> players, int eraNumber) {
        for (var roundNumber = 1; roundNumber <= 3; roundNumber++) {
            var round = roundNumber;
            var states = players.stream()
                    .collect(Collectors.toMap(
                            player -> player, player -> awaitPlayerAtEraRound(player, gameId, eraNumber, round)));
            // Checked every round of every era, not just once at the start: a premature reveal in a later
            // era would otherwise slip past a scenario that only ever looked at era one.
            for (var state : states.values()) {
                assertThat(state.players())
                        .as("factions stay hidden before FactionRevealed (era %d round %d)", eraNumber, round)
                        .allSatisfy(view -> assertThat(view.faction()).isNull());
            }
            for (var player : players) {
                playAnyEligibleAction(player, players, gameId, eraNumber, round, states.get(player));
            }
        }
    }

    // A lightweight, always-non-probing submission: issue #7's scenario already proves duplicate/forged/
    // round-ineligible rejection and the once-per-era special budget, so this only needs to advance every
    // round with a valid action wherever one is available -- a player with nothing eligible is simply
    // skipped, letting the round's own timer close it, exactly as production allows.
    private void playAnyEligibleAction(
            Actor player, List<Actor> allPlayers, UUID gameId, int eraNumber, int roundNumber, PlayerState state) {
        var eligible = state.hand().stream().filter(Card::isPlayableThisRound).toList();
        if (eligible.isEmpty()) {
            return;
        }

        var card = eligible.getFirst();
        if (PLAYER_TARGETING_CARD_TYPES.contains(card.cardType())) {
            var opponent = allPlayers.stream()
                    .filter(candidate -> !candidate.equals(player))
                    .findFirst()
                    .orElseThrow();
            scenario.as(player)
                    .playCardTargetingPlayer(gameId, eraNumber, roundNumber, card, opponent.playerId())
                    .assertStatus(202);
            return;
        }

        var event = state.activeEvents().getFirst();
        var sourceOutcomeId = TWO_OUTCOME_CARD_TYPES.contains(card.cardType()) ? sourceOutcome(event) : null;
        scenario.as(player)
                .playCard(gameId, eraNumber, roundNumber, card, event.eventId(), sourceOutcomeId, targetOutcome(event))
                .assertStatus(202);
    }

    private void verifyFinalScoresAgreeAcrossServices(
            UUID gameId, Actor host, List<Actor> players, Map<Actor, PlayerState> finalStates) {
        // FactionRevealed's unidentified-faction bonus is awarded reactively in game-service after
        // GameEnded already published its score snapshot (temporal-rift-gdd.md §7), so game-service's own
        // /scores can briefly lag read-service's projection. Polling for convergence rather than comparing
        // two independent one-shot reads is what makes this assertion race-free instead of flaky.
        var finalScores = scenario.awaitScores(
                host,
                gameId,
                board -> players.stream().allMatch(player -> scoresAgree(board, player, finalStates.get(player))),
                "game-service scores converge with read-service's final per-player scores and revealed factions");

        for (var player : players) {
            var expected = finalStates.get(player);
            var actual = finalScores.forPlayer(player.playerId()).orElseThrow();
            assertThat(actual.score())
                    .as("%s's final score agrees between game-service and read-service", player.name())
                    .isEqualTo(expected.myScore());
            assertThat(actual.faction())
                    .as("%s's revealed faction agrees between game-service and read-service", player.name())
                    .isEqualTo(readSideFaction(expected, player));
        }
    }

    private static boolean scoresAgree(ScoreBoard board, Actor player, PlayerState readState) {
        return board.forPlayer(player.playerId())
                .filter(entry -> entry.score() == readState.myScore())
                .filter(entry -> entry.faction() != null && entry.faction().equals(readSideFaction(readState, player)))
                .isPresent();
    }

    private static String readSideFaction(PlayerState state, Actor player) {
        return state.players().stream()
                .filter(view -> view.playerId().equals(player.playerId()))
                .findFirst()
                .map(PlayerView::faction)
                .orElseThrow();
    }

    private void playEraOneRoundOne(
            UUID gameId,
            Actor host,
            List<Actor> players,
            Actor budgetedPlayer,
            String budgetedSpecial,
            boolean[] roundIneligibilityProbed,
            boolean[] playerTargetingProbed) {
        for (var player : players) {
            var state = awaitPlayerAtRound(player, gameId, 1);
            if (player.equals(budgetedPlayer)) {
                var targetEvent = state.activeEvents().getFirst();
                scenario.as(player)
                        .playSpecial(gameId, 1, 1, budgetedSpecial, targetEvent.eventId(), targetOutcome(targetEvent))
                        .assertStatus(202);
            } else {
                submitEligibleAction(
                        player, players, gameId, 1, state, roundIneligibilityProbed, playerTargetingProbed);
            }
        }
        awaitRoundClosed(host, gameId, 1, 1, players.size());
    }

    private void playEraOneRoundTwo(
            UUID gameId,
            Actor host,
            List<Actor> players,
            Actor budgetedPlayer,
            String budgetedSpecial,
            boolean[] roundIneligibilityProbed,
            boolean[] playerTargetingProbed) {
        var otherPlayers = players.stream()
                .filter(player -> !player.equals(budgetedPlayer))
                .toList();
        var otherStates = otherPlayers.stream()
                .collect(Collectors.toMap(player -> player, player -> awaitPlayerAtRound(player, gameId, 2)));

        // Duplicate submission: PlayCardCommandHandler looks the card up in hand before ActionRound.submit()
        // ever runs its own pendingPlayerIds check, so a genuine duplicate probe needs a second, still-held
        // card, not a resubmission of the same one (that would hit CardNotInHandException, 422-01, instead of
        // the duplicate check). Which of the two non-budgeted players still holds two such cards after round
        // 1's play is a property of the random deal, not something either specific player is guaranteed to
        // have — so the prober is picked dynamically (whoever has the most) instead of assuming it's always
        // the first one.
        var duplicateProber = otherPlayers.stream()
                .max(Comparator.comparingInt(player ->
                        eligibleEventTargetingCards(otherStates.get(player)).size()))
                .orElseThrow();
        var plainSubmitter = otherPlayers.stream()
                .filter(player -> !player.equals(duplicateProber))
                .findFirst()
                .orElseThrow();

        var proberCards = eligibleEventTargetingCards(otherStates.get(duplicateProber));
        assertThat(proberCards)
                .as("at least one non-budgeted player needs two eligible event-targeting cards to probe duplicate"
                        + " submission")
                .hasSizeGreaterThanOrEqualTo(2);
        var firstCard = proberCards.get(0);
        var secondCard = proberCards.get(1);
        var proberEvent = otherStates.get(duplicateProber).activeEvents().getFirst();
        var firstSource = TWO_OUTCOME_CARD_TYPES.contains(firstCard.cardType()) ? sourceOutcome(proberEvent) : null;
        scenario.as(duplicateProber)
                .playCard(gameId, 1, 2, firstCard, proberEvent.eventId(), firstSource, targetOutcome(proberEvent))
                .assertStatus(202);

        var secondSource = TWO_OUTCOME_CARD_TYPES.contains(secondCard.cardType()) ? sourceOutcome(proberEvent) : null;
        var duplicate = scenario.as(duplicateProber)
                .playCard(gameId, 1, 2, secondCard, proberEvent.eventId(), secondSource, targetOutcome(proberEvent));
        duplicate.assertStatus(409);
        assertThat(duplicate.body().path("code").asText()).isEqualTo("409-02");

        // Forged target: an owned card against a fabricated event/outcome id is rejected before round
        // mutation, so the submitting player can still submit normally afterward.
        var budgetedState = awaitPlayerAtRound(budgetedPlayer, gameId, 2);
        var budgetedCard = eligibleEventTargetingCard(budgetedState);
        var forged = scenario.as(budgetedPlayer)
                .playCard(gameId, 1, 2, budgetedCard, UUID.randomUUID(), null, UUID.randomUUID());
        forged.assertStatus(422);
        assertThat(forged.body().path("code").asText()).isEqualTo("422-06");

        var budgetedEvent = budgetedState.activeEvents().getFirst();
        var budgetedSource =
                TWO_OUTCOME_CARD_TYPES.contains(budgetedCard.cardType()) ? sourceOutcome(budgetedEvent) : null;
        scenario.as(budgetedPlayer)
                .playCard(
                        gameId,
                        1,
                        2,
                        budgetedCard,
                        budgetedEvent.eventId(),
                        budgetedSource,
                        targetOutcome(budgetedEvent))
                .assertStatus(202);

        // Once-per-era budget: the budget check runs before ActionRound's own duplicate check, so reusing
        // the special after already submitting a card this round still fails with the budget's own code.
        var reuse = scenario.as(budgetedPlayer)
                .playSpecial(gameId, 1, 2, budgetedSpecial, budgetedEvent.eventId(), targetOutcome(budgetedEvent));
        reuse.assertStatus(409);
        assertThat(reuse.body().path("code").asText()).isEqualTo("409-10");

        submitEligibleAction(
                plainSubmitter,
                players,
                gameId,
                2,
                otherStates.get(plainSubmitter),
                roundIneligibilityProbed,
                playerTargetingProbed);

        awaitRoundClosed(host, gameId, 1, 2, players.size());
    }

    private void playEraOneRoundThree(
            UUID gameId,
            Actor host,
            List<Actor> players,
            boolean[] roundIneligibilityProbed,
            boolean[] playerTargetingProbed) {
        var states = players.stream()
                .collect(Collectors.toMap(player -> player, player -> awaitPlayerAtRound(player, gameId, 3)));
        var submitters = players.stream()
                .filter(player -> hasEligibleCard(states.get(player)))
                .limit(2)
                .toList();
        assertThat(submitters)
                .as("two players need a Round 3-eligible card for the timer-close path")
                .hasSize(2);
        for (var player : submitters) {
            submitEligibleAction(
                    player, players, gameId, 3, states.get(player), roundIneligibilityProbed, playerTargetingProbed);
        }
        awaitRoundClosed(host, gameId, 1, 3, submitters.size());
    }

    /**
     * Fills a player's round action. Covers two rules with guaranteed material this scenario forces into
     * every deal (see {@code hand-deal-forced-types} in compose.e2e.yml): a round-ineligible card, probed
     * and confirmed non-consuming before the real submission, and a player-targeting card, submitted against
     * a forged then a real opponent. Which specific call ends up exercising each one still depends on
     * per-round eligibility and turn order, so both are opportunistic per call — the caller tracks whether
     * each fires at least once across the whole scenario.
     */
    private void submitEligibleAction(
            Actor player,
            List<Actor> allPlayers,
            UUID gameId,
            int roundNumber,
            PlayerState state,
            boolean[] roundIneligibilityProbed,
            boolean[] playerTargetingProbed) {
        if (probeRoundIneligibilityIfAvailable(player, gameId, roundNumber, state)) {
            roundIneligibilityProbed[0] = true;
        }

        var playerTargetingCard = state.hand().stream()
                .filter(Card::isPlayableThisRound)
                .filter(candidate -> PLAYER_TARGETING_CARD_TYPES.contains(candidate.cardType()))
                .findFirst();
        if (playerTargetingCard.isPresent()) {
            var card = playerTargetingCard.get();
            scenario.as(player)
                    .playCardTargetingPlayer(gameId, 1, roundNumber, card, UUID.randomUUID())
                    .assertStatus(404);
            var opponent = allPlayers.stream()
                    .filter(candidate -> !candidate.equals(player))
                    .findFirst()
                    .orElseThrow();
            scenario.as(player)
                    .playCardTargetingPlayer(gameId, 1, roundNumber, card, opponent.playerId())
                    .assertStatus(202);
            playerTargetingProbed[0] = true;
            return;
        }

        playEligibleCard(player, gameId, roundNumber, state);
    }

    private boolean probeRoundIneligibilityIfAvailable(Actor player, UUID gameId, int roundNumber, PlayerState state) {
        var ineligibleCard = state.hand().stream()
                .filter(card -> !card.isPlayableThisRound())
                .findFirst();
        if (ineligibleCard.isEmpty()) {
            return false;
        }

        var targetEvent = state.activeEvents().getFirst();
        var rejected = scenario.as(player)
                .playCard(
                        gameId,
                        1,
                        roundNumber,
                        ineligibleCard.get(),
                        targetEvent.eventId(),
                        null,
                        targetOutcome(targetEvent));
        rejected.assertStatus(422);
        assertThat(rejected.body().path("code").asText()).isEqualTo("422-12");

        var status = scenario.as(player).getRoundStatus(gameId, 1, roundNumber).assertStatus(200);
        assertThat(RoundState.from(status.body()).pendingPlayerIds()).contains(player.playerId());
        return true;
    }

    private void playEligibleCard(Actor player, UUID gameId, int roundNumber, PlayerState state) {
        var card = eligibleEventTargetingCard(state);
        var targetEvent = state.activeEvents().getFirst();
        var sourceOutcomeId = TWO_OUTCOME_CARD_TYPES.contains(card.cardType()) ? sourceOutcome(targetEvent) : null;
        scenario.as(player)
                .playCard(
                        gameId,
                        1,
                        roundNumber,
                        card,
                        targetEvent.eventId(),
                        sourceOutcomeId,
                        targetOutcome(targetEvent))
                .assertStatus(202);
    }

    private static Card eligibleEventTargetingCard(PlayerState state) {
        return eligibleEventTargetingCards(state).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("No eligible event-targeting card available in " + state.hand()));
    }

    private static List<Card> eligibleEventTargetingCards(PlayerState state) {
        return state.hand().stream()
                .filter(Card::isPlayableThisRound)
                .filter(candidate -> !PLAYER_TARGETING_CARD_TYPES.contains(candidate.cardType()))
                .toList();
    }

    private static boolean hasEligibleCard(PlayerState state) {
        return state.hand().stream().anyMatch(Card::isPlayableThisRound);
    }

    private static boolean isRoundRestrictedType(String cardType) {
        return ROUND_ONE_INELIGIBLE_TYPES.contains(cardType) || ROUND_THREE_INELIGIBLE_TYPES.contains(cardType);
    }

    private static UUID targetOutcome(ActiveEvent event) {
        return event.outcomeIds().getFirst();
    }

    private static UUID sourceOutcome(ActiveEvent event) {
        return event.outcomeIds().get(1);
    }

    private PlayerState awaitPlayerAtRound(Actor player, UUID gameId, int roundNumber) {
        return awaitPlayerAtEraRound(player, gameId, 1, roundNumber);
    }

    private PlayerState awaitPlayerAtEraRound(Actor player, UUID gameId, int eraNumber, int roundNumber) {
        return scenario.awaitPlayerState(
                player,
                gameId,
                candidate ->
                        candidate.eraNumber() == eraNumber && ("ACTION_ROUND_" + roundNumber).equals(candidate.phase()),
                player.name() + " reaches era " + eraNumber + " round " + roundNumber);
    }

    private void awaitRoundClosed(
            Actor actor, UUID gameId, int eraNumber, int roundNumber, int expectedSubmittedCount) {
        scenario.awaitRoundState(
                actor,
                gameId,
                eraNumber,
                roundNumber,
                candidate ->
                        "CLOSED".equals(candidate.status()) && candidate.submittedCount() == expectedSubmittedCount);
    }

    private void verifyScoresAndLaterEraProjection(
            UUID gameId, Actor host, List<Actor> players, Map<Actor, PlayerState> eraOneStates) {
        var scores = scenario.awaitScores(
                host, gameId, board -> board.eraNumber() == 1 && board.scores().size() == players.size());

        for (var player : players) {
            var laterState = scenario.awaitPlayerState(
                    player,
                    gameId,
                    candidate -> candidate.eraNumber() >= 2 && candidate.hand().size() == 5,
                    player.name() + " reaches a later era with a fresh hand");
            assertThat(laterState.myScore())
                    .isEqualTo(scores.forPlayer(player.playerId()).orElseThrow().score());
            assertThat(laterState.activeEvents()).hasSize(3);

            var eraOneCardIds = eraOneStates.get(player).hand().stream()
                    .map(Card::cardInstanceId)
                    .collect(Collectors.toSet());
            var laterCardIds =
                    laterState.hand().stream().map(Card::cardInstanceId).collect(Collectors.toSet());
            assertThat(laterCardIds)
                    .as("%s's later-era hand reuses none of era one's card instances", player.name())
                    .doesNotContainAnyElementsOf(eraOneCardIds);

            var history = scenario.awaitGameHistory(
                    player,
                    gameId,
                    candidate -> candidate.era(2).isPresent(),
                    player.name() + " era-two history is durable");
            assertThat(history.era(2).orElseThrow().myHand()).hasSize(5);
        }
    }

    private void verifySpecialBudgetResetsInEraTwo(UUID gameId, Actor player, String special) {
        var state = scenario.awaitPlayerState(
                player,
                gameId,
                candidate -> candidate.eraNumber() == 2 && "ACTION_ROUND_1".equals(candidate.phase()),
                player.name() + " reaches era two round one");
        var targetEvent = state.activeEvents().getFirst();
        scenario.as(player)
                .playSpecial(gameId, 2, 1, special, targetEvent.eventId(), targetOutcome(targetEvent))
                .assertStatus(202);
    }

    private UUID startGameWithThreePlayers(Actor host, Actor playerTwo, Actor playerThree) {
        var created = scenario.as(host).createLobby().assertStatus(201);
        var lobbyId = UUID.fromString(created.body().path("lobbyId").asText());

        assertThat(scenario.as(playerTwo)
                        .joinLobby(lobbyId)
                        .assertStatus(200)
                        .body()
                        .path("currentPlayers")
                        .size())
                .isEqualTo(2);
        assertThat(scenario.as(playerThree)
                        .joinLobby(lobbyId)
                        .assertStatus(200)
                        .body()
                        .path("currentPlayers")
                        .size())
                .isEqualTo(3);
        scenario.as(playerTwo).startGame(lobbyId).assertStatus(403);

        var started = scenario.as(host).startGame(lobbyId).assertStatus(202);
        var gameId = UUID.fromString(started.body().path("gameId").asText());
        assertThat(gameId).isNotEqualTo(lobbyId);
        return gameId;
    }

    // `hand` is the confirmed playable hand and `pendingHand` the unresolved seven-card deal — they are
    // separate fields in the contract, not two states of one field. HandDealt fills only
    // pendingHandSelection and never touches myHand, so myHand keeps showing the previous era's five
    // selected cards (empty only for era one, which has none) until this era's own HandSelected resolves it.
    private void dealAndSelectEraOneHands(UUID gameId, List<Actor> players) {
        dealAndSelectHand(gameId, players, 1, TemporalRiftSystemIT::chooseEraOneHand);
    }

    private void dealAndSelectHand(
            UUID gameId, List<Actor> players, int eraNumber, Function<List<Card>, List<UUID>> handChooser) {
        // Round 1 cannot open until every player has selected, so all offers are awaited and selected
        // before any player's post-selection state is awaited — awaiting the post-selection state per player
        // in a single pass would deadlock the first player on a round-open signal that depends on selections
        // this loop hasn't submitted yet for the others.
        var pendingOffers = new LinkedHashMap<Actor, PlayerState>();
        for (var player : players) {
            pendingOffers.put(
                    player,
                    scenario.awaitPlayerState(
                            player,
                            gameId,
                            candidate -> candidate.eraNumber() == eraNumber
                                    && candidate.pendingHand().size() == 7,
                            player.name() + " receives the pending seven-card era-" + eraNumber + " deal"));
        }
        for (var player : players) {
            var keptCardIds = handChooser.apply(pendingOffers.get(player).pendingHand());
            scenario.as(player).selectHand(gameId, eraNumber, keptCardIds).assertStatus(202);
        }
    }

    // Unlike chooseEraOneHand (which deliberately keeps round-restricted cards to probe rejection —
    // already proven by #7's scenario), this keeps only cards playable in every round so the game-end
    // scenario's per-round submission doesn't stall on timers waiting for a player with nothing eligible.
    private static List<UUID> chooseAlwaysPlayableHand(List<Card> offer) {
        var kept = new ArrayList<Card>();
        offer.stream()
                .filter(card -> !isRoundRestrictedType(card.cardType()))
                .limit(5)
                .forEach(kept::add);
        offer.stream()
                .filter(card -> !kept.contains(card))
                .limit(5 - kept.size())
                .forEach(kept::add);
        return kept.stream().map(Card::cardInstanceId).toList();
    }

    private static List<UUID> chooseEraOneHand(List<Card> offer) {
        var kept = new ArrayList<Card>();
        offer.stream()
                .filter(card -> isRoundRestrictedType(card.cardType()))
                .findFirst()
                .ifPresent(kept::add);
        // Same bias, same reason, for the other opportunistic probe: keep a player-targeting card too if the
        // deal offers one, so the player-targeting counterplay assertion has material as often as the
        // round-ineligibility one does — not a guarantee (no deal-override hook exists to force either), but
        // both probes get the same treatment instead of only one of them being deliberately favored.
        offer.stream()
                .filter(card -> !kept.contains(card))
                .filter(card -> PLAYER_TARGETING_CARD_TYPES.contains(card.cardType()))
                .findFirst()
                .ifPresent(kept::add);
        offer.stream()
                .filter(card -> !kept.contains(card))
                .sorted(Comparator.comparingInt(card -> ROUND_THREE_INELIGIBLE_TYPES.contains(card.cardType()) ? 1 : 0))
                .limit(5 - kept.size())
                .forEach(kept::add);
        return kept.stream().map(Card::cardInstanceId).toList();
    }

    private Map<Actor, PlayerState> awaitEraOneStatesAndRejectOutsider(
            UUID gameId, List<Actor> players, Actor outsider) {
        var states = new LinkedHashMap<Actor, PlayerState>();
        for (var player : players) {
            var state = scenario.awaitPlayerState(
                    player,
                    gameId,
                    candidate -> candidate.eraNumber() == 1
                            && "ACTION_ROUND_1".equals(candidate.phase())
                            && candidate.myFaction() != null
                            && candidate.hand().size() == 5
                            && candidate.activeEvents().size() == 3
                            && candidate.players().size() == 3,
                    player.name() + " receives private era-one state");
            assertThat(state.gameId()).isEqualTo(gameId);
            assertThat(state.players())
                    .allSatisfy(view -> assertThat(view.faction()).isNull());
            states.put(player, state);
        }
        scenario.as(outsider).getPlayerState(gameId).assertStatus(404);
        return states;
    }

    private void assertCentralizedLogMetadata() {
        // Each service's presence is checked with its own app_name-filtered query, not by scanning the most-recent-N
        // window once for every service: read-service logs only warnings on anomalies (nothing on its success
        // path), so a busy run of game-service/timeline-service Kafka chatter can push its still-present events out
        // of a shared recent-N window without the pipeline itself being broken for it. `| limit 1` keeps each
        // response a single JSON object, matching JsonHttpClient's single-document body parsing — VictoriaLogs
        // returns newline-delimited JSON for multi-record results.
        for (var tag : CENTRALIZED_LOG_SERVICE_TAGS) {
            var response = httpClient.get(victoriaLogsAppNameUri(tag), null).assertStatus(200);
            assertThat(response.rawBody())
                    .as("VictoriaLogs has centralized at least one log event with app_name %s", tag)
                    .isNotBlank();
        }

        var response = httpClient.get(VICTORIALOGS_TRACE_QUERY_URI, null).assertStatus(200);
        assertThat(response.rawBody())
                .as("at least one centralized log event contains traceId (and, once unpacked, spanId alongside it)")
                .isNotBlank();
    }

    private static URI victoriaLogsAppNameUri(String appName) {
        return victoriaLogsQueryUri("app_name:=\"" + appName + "\" | limit 1");
    }

    private static URI victoriaLogsQueryUri(String logsQlQuery) {
        var query = URLEncoder.encode(logsQlQuery, StandardCharsets.UTF_8);
        return URI.create("http://localhost:15341/select/logsql/query?query=" + query);
    }
}
