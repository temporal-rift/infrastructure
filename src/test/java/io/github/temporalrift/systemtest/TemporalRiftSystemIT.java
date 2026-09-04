package io.github.temporalrift.systemtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.systemtest.TemporalRiftScenario.ActiveEvent;
import io.github.temporalrift.systemtest.TemporalRiftScenario.Card;
import io.github.temporalrift.systemtest.TemporalRiftScenario.PlayerState;

class TemporalRiftSystemIT {

    private static final URI GAME_HEALTH_URI = URI.create("http://localhost:18080/actuator/health");
    private static final URI VICTORIALOGS_TRACE_QUERY_URI =
            victoriaLogsQueryUri("* | unpack_json | traceId:* | spanId:* | limit 1");
    private static final List<String> CENTRALIZED_LOG_SERVICE_TAGS =
            List.of("game-service", "timeline-service", "read-service");

    private static final Map<String, String> SAFE_SPECIAL_BY_FACTION = Map.of(
            "ERASERS", "ANNIHILATE",
            "PROPHETS", "FORESIGHT",
            "REVISIONISTS", "REWRITE");

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
    void completeEraTraversesSessionActionTimelineScoringAndProjection() {
        var host = Actor.named("Host");
        var playerTwo = Actor.named("Player two");
        var playerThree = Actor.named("Player three");
        var outsider = Actor.named("Outsider");
        var players = List.of(host, playerTwo, playerThree);

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

        // Deal-7-keep-5 (game-service#121/#127, read-service#46): the seven-card deal arrives in
        // pendingHandSelection, not myHand — myHand stays empty until the player's selection resolves, so the
        // deal must be read from the pending field. Round 1 cannot open until every player has selected, so
        // all three offers are awaited and selected before any player's post-selection state is awaited —
        // awaiting the post-selection state per player in a single pass would deadlock the first player on a
        // round-open signal that depends on selections this loop hasn't submitted yet for the other two.
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
            var keptCardIds = pendingOffers.get(player).pendingHand().stream()
                    .map(Card::cardInstanceId)
                    .limit(5)
                    .toList();
            scenario.as(player).selectHand(gameId, 1, keptCardIds).assertStatus(202);
        }

        var initialStates = new LinkedHashMap<Actor, PlayerState>();
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
            initialStates.put(player, state);
        }
        scenario.as(outsider).getPlayerState(gameId).assertStatus(404);

        var originalEventIds = initialStates.get(host).activeEvents().stream()
                .map(ActiveEvent::eventId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var targetEvent = initialStates.get(host).activeEvents().getFirst();
        var availableCards = new LinkedHashMap<Actor, ArrayDeque<Card>>();
        initialStates.forEach((player, state) -> availableCards.put(player, new ArrayDeque<>(state.hand())));

        assertRoundOpen(
                gameId,
                1,
                1,
                host,
                0,
                players.stream().map(Actor::playerId).collect(java.util.stream.Collectors.toSet()));

        var hostFirstCard = availableCards.get(host).peekFirst();
        scenario.as(host)
                .playCard(
                        gameId,
                        1,
                        1,
                        hostFirstCard,
                        UUID.randomUUID(),
                        sourceOutcome(hostFirstCard, targetEvent),
                        targetOutcome(targetEvent))
                .assertStatus(422);
        assertRoundOpen(
                gameId,
                1,
                1,
                host,
                0,
                players.stream().map(Actor::playerId).collect(java.util.stream.Collectors.toSet()));

        playNextCard(host, availableCards, gameId, 1, 1, targetEvent).assertStatus(202);
        assertRoundOpen(gameId, 1, 1, host, 1, Set.of(playerTwo.playerId(), playerThree.playerId()));

        var hostDuplicateCard = availableCards.get(host).peekFirst();
        scenario.as(host)
                .playCard(
                        gameId,
                        1,
                        1,
                        hostDuplicateCard,
                        targetEvent.eventId(),
                        sourceOutcome(hostDuplicateCard, targetEvent),
                        targetOutcome(targetEvent))
                .assertStatus(409);
        assertRoundOpen(gameId, 1, 1, host, 1, Set.of(playerTwo.playerId(), playerThree.playerId()));

        playNextCard(playerTwo, availableCards, gameId, 1, 1, targetEvent).assertStatus(202);
        assertRoundOpen(gameId, 1, 1, host, 2, Set.of(playerThree.playerId()));

        var roundOneClosingResponse = playNextCard(playerThree, availableCards, gameId, 1, 1, targetEvent)
                .assertStatus(202);
        assertThat(roundOneClosingResponse.body().path("roundClosed").asBoolean())
                .isTrue();
        var closedRoundOne = scenario.awaitRoundState(host, gameId, 1, 1, state -> "CLOSED".equals(state.status()));
        assertThat(closedRoundOne.submittedCount()).isEqualTo(3);
        assertThat(closedRoundOne.pendingPlayerIds()).isEmpty();

        var roundTwoState = scenario.awaitPlayerState(
                host,
                gameId,
                candidate -> candidate.eraNumber() == 1 && "ACTION_ROUND_2".equals(candidate.phase()),
                "round two is projected");
        assertThat(roundTwoState.activeEvents()).hasSize(3);
        assertRoundOpen(
                gameId,
                1,
                2,
                host,
                0,
                players.stream().map(Actor::playerId).collect(java.util.stream.Collectors.toSet()));

        var specialPlayer = players.stream()
                .filter(player -> SAFE_SPECIAL_BY_FACTION.containsKey(
                        initialStates.get(player).myFaction()))
                .findFirst()
                .orElseThrow();
        var special =
                SAFE_SPECIAL_BY_FACTION.get(initialStates.get(specialPlayer).myFaction());
        scenario.as(specialPlayer)
                .playSpecial(gameId, 1, 2, special, targetEvent.eventId(), targetOutcome(targetEvent))
                .assertStatus(202);
        assertThat(special).isIn("ANNIHILATE", "FORESIGHT", "REWRITE");

        var roundTwoCardPlayers =
                players.stream().filter(player -> !player.equals(specialPlayer)).toList();
        playNextCard(roundTwoCardPlayers.getFirst(), availableCards, gameId, 1, 2, targetEvent)
                .assertStatus(202);
        var roundTwoClosingResponse = playNextCard(
                        roundTwoCardPlayers.getLast(), availableCards, gameId, 1, 2, targetEvent)
                .assertStatus(202);
        assertThat(roundTwoClosingResponse.body().path("roundClosed").asBoolean())
                .isTrue();
        var closedRoundTwo = scenario.awaitRoundState(host, gameId, 1, 2, state -> "CLOSED".equals(state.status()));
        assertThat(closedRoundTwo.submittedCount()).isEqualTo(3);

        scenario.awaitPlayerState(
                host,
                gameId,
                candidate -> candidate.eraNumber() == 1 && "ACTION_ROUND_3".equals(candidate.phase()),
                "round three is projected");
        assertRoundOpen(
                gameId,
                1,
                3,
                host,
                0,
                players.stream().map(Actor::playerId).collect(java.util.stream.Collectors.toSet()));

        playNextCard(host, availableCards, gameId, 1, 3, targetEvent).assertStatus(202);
        playNextCard(playerTwo, availableCards, gameId, 1, 3, targetEvent).assertStatus(202);
        assertRoundOpen(gameId, 1, 3, host, 2, Set.of(playerThree.playerId()));

        var timedOutRound = scenario.awaitRoundState(host, gameId, 1, 3, state -> "CLOSED".equals(state.status()));
        assertThat(timedOutRound.submittedCount()).isEqualTo(2);
        assertThat(timedOutRound.pendingPlayerIds()).isEmpty();

        var scores = scenario.awaitScores(
                host,
                gameId,
                scoreBoard -> scoreBoard.eraNumber() == 1
                        && scoreBoard
                                .scores()
                                .keySet()
                                .containsAll(
                                        players.stream().map(Actor::playerId).toList()));
        assertThat(scores.scores()).hasSize(3);

        for (var player : players) {
            // Era 2 is only ever observed here, never played through — its rounds auto-close via the
            // action-round timer. The live snapshot below is inherently racy against that timer (it may
            // observe era 2 or, if the timer has already advanced the game further, a later era), so
            // era 2's dealt hand is verified separately below through the durable history projection,
            // which is unaffected by how far the live snapshot or the timer has progressed.
            //
            // The score and hand-size checks are folded into this same predicate rather than compared
            // afterward: read-service's projection can only ever lag game-service's authoritative total
            // (never skip ahead of it), and dwells on each era's total for a full era (~25s at the e2e
            // 8s round timer) versus this poll's 200ms interval — so waiting for the projection to reach
            // era 1's already-known total, in the same snapshot where eraNumber first reaches 2, is
            // race-free. Comparing against a value fetched *after* observing the later-era snapshot would
            // invert the race instead of removing it, since game-service's authoritative total can move
            // ahead again (era 2 also scores) in the time between the two reads. hand().size() == 5 is
            // likewise race-free for eraNumber >= 2 — no card is ever played after era 1, so every era
            // from 2 onward has a fresh, untouched 5-card hand — and is the only black-box proof that
            // read-service replaces the projected hand each era instead of accumulating it.
            var expectedScore = scores.scores().get(player.playerId());
            var laterEraState = scenario.awaitPlayerState(
                    player,
                    gameId,
                    candidate -> candidate.eraNumber() >= 2
                            && candidate.hand().size() == 5
                            && candidate.myScore() == expectedScore
                            && candidate.activeEvents().size() == 3
                            && candidate.activeEvents().stream()
                                    .map(ActiveEvent::eventId)
                                    .noneMatch(originalEventIds::contains),
                    player.name() + " receives a later-era projection after resolution and scoring");
            assertThat(laterEraState.players())
                    .filteredOn(view -> view.playerId().equals(player.playerId()))
                    .singleElement()
                    .satisfies(view -> assertThat(view.score()).isEqualTo(laterEraState.myScore()));

            var eraTwoHistory = scenario.awaitGameHistory(
                    player,
                    gameId,
                    candidate -> candidate
                            .era(2)
                            .map(era -> era.myHand().size() == 5)
                            .orElse(false),
                    player.name() + " has a durable record of their era-two dealt hand");
            assertThat(eraTwoHistory.era(2).orElseThrow().myHand()).hasSize(5);
        }
    }

    private JsonHttpClient.Response playNextCard(
            Actor player,
            Map<Actor, ArrayDeque<Card>> availableCards,
            UUID gameId,
            int eraNumber,
            int roundNumber,
            ActiveEvent targetEvent) {
        var card = availableCards.get(player).removeFirst();
        return scenario.as(player)
                .playCard(
                        gameId,
                        eraNumber,
                        roundNumber,
                        card,
                        targetEvent.eventId(),
                        sourceOutcome(card, targetEvent),
                        targetOutcome(targetEvent));
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

    private void assertRoundOpen(
            UUID gameId, int eraNumber, int roundNumber, Actor observer, int submittedCount, Set<UUID> pendingPlayers) {
        var state = scenario.awaitRoundState(
                observer,
                gameId,
                eraNumber,
                roundNumber,
                candidate -> "OPEN".equals(candidate.status())
                        && candidate.submittedCount() == submittedCount
                        && Set.copyOf(candidate.pendingPlayerIds()).equals(pendingPlayers));
        assertThat(state.totalPlayers()).isEqualTo(3);
        assertThat(state.eraNumber()).isEqualTo(eraNumber);
        assertThat(state.roundNumber()).isEqualTo(roundNumber);
    }

    private static UUID targetOutcome(ActiveEvent event) {
        return event.outcomeIds().getFirst();
    }

    private static final Set<String> TWO_OUTCOME_CARD_TYPES = Set.of("SWING", "COLLIDE");

    private static UUID sourceOutcome(Card card, ActiveEvent event) {
        return TWO_OUTCOME_CARD_TYPES.contains(card.cardType())
                ? event.outcomeIds().get(1)
                : null;
    }
}
