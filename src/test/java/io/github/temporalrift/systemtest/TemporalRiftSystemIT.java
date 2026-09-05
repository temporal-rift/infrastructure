package io.github.temporalrift.systemtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.systemtest.TemporalRiftScenario.Card;
import io.github.temporalrift.systemtest.TemporalRiftScenario.PlayerState;

class TemporalRiftSystemIT {

    private static final URI GAME_HEALTH_URI = URI.create("http://localhost:18080/actuator/health");
    private static final URI VICTORIALOGS_TRACE_QUERY_URI =
            victoriaLogsQueryUri("* | unpack_json | traceId:* | spanId:* | limit 1");
    private static final List<String> CENTRALIZED_LOG_SERVICE_TAGS =
            List.of("game-service", "timeline-service", "read-service");

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

    // The three-round card-play lifecycle (task 3.3 of `add-system-e2e-test`) is deliberately not here — it
    // selects valid cards and an eligible faction special, both of which the in-flight card-system rework
    // (game-service#121, #122, #123; timeline-service#45) changes. It is tracked in
    // https://github.com/temporal-rift/infrastructure/issues/7 and lands once that rework settles. This test
    // covers only the pre-game, rules-independent surface: game start, private era-one state, and the
    // privacy/non-participant guarantees that hold regardless of what a card-play round may legally submit.
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
            var keptCardIds = pendingOffers.get(player).pendingHand().stream()
                    .map(Card::cardInstanceId)
                    .limit(5)
                    .toList();
            scenario.as(player).selectHand(gameId, 1, keptCardIds).assertStatus(202);
        }
    }

    private void awaitEraOneStatesAndRejectOutsider(UUID gameId, List<Actor> players, Actor outsider) {
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
        }
        scenario.as(outsider).getPlayerState(gameId).assertStatus(404);
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
