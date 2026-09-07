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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.systemtest.TemporalRiftScenario.ActiveEvent;
import io.github.temporalrift.systemtest.TemporalRiftScenario.Card;
import io.github.temporalrift.systemtest.TemporalRiftScenario.PlayerState;
import io.github.temporalrift.systemtest.TemporalRiftScenario.RoundState;

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
        return scenario.awaitPlayerState(
                player,
                gameId,
                candidate -> candidate.eraNumber() == 1 && ("ACTION_ROUND_" + roundNumber).equals(candidate.phase()),
                player.name() + " reaches round " + roundNumber);
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
            assertThat(laterState.myScore()).isEqualTo(scores.scores().get(player.playerId()));
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
    // pendingHandSelection; myHand stays empty until HandSelected resolves the choice.
    private void dealAndSelectEraOneHands(UUID gameId, List<Actor> players) {
        // Round 1 cannot open until every player has selected, so all three offers are awaited and selected
        // before any player's post-selection state is awaited — awaiting the post-selection state per player
        // in a single pass would deadlock the first player on a round-open signal that depends on selections
        // this loop hasn't submitted yet for the other two.
        var pendingOffers = new LinkedHashMap<Actor, PlayerState>();
        for (var player : players) {
            pendingOffers.put(
                    player,
                    scenario.awaitPlayerState(
                            player,
                            gameId,
                            candidate -> candidate.eraNumber() == 1
                                    && candidate.pendingHand().size() == 7
                                    && candidate.hand().isEmpty(),
                            player.name() + " receives the pending seven-card era-one deal"));
        }
        for (var player : players) {
            var keptCardIds = chooseEraOneHand(pendingOffers.get(player).pendingHand());
            scenario.as(player).selectHand(gameId, 1, keptCardIds).assertStatus(202);
        }
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
