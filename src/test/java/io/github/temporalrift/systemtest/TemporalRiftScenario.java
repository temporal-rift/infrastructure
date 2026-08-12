package io.github.temporalrift.systemtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import tools.jackson.databind.JsonNode;

final class TemporalRiftScenario {

    private static final URI GAME_API = URI.create("http://localhost:18080/api/v1");
    private static final URI READ_API = URI.create("http://localhost:18082/api/v1");
    private static final Duration TRANSITION_TIMEOUT = Duration.ofSeconds(180);

    private final JsonHttpClient http = new JsonHttpClient();

    ActorActions as(Actor actor) {
        return new ActorActions(actor);
    }

    JsonHttpClient.Response createLobbyWithoutAuthentication(String playerName) {
        return http.post(gameUri("/lobbies"), null, Map.of("playerName", playerName));
    }

    PlayerState awaitPlayerState(Actor actor, UUID gameId, Predicate<PlayerState> expected, String description) {
        return eventually(
                () -> {
                    var response = as(actor).getPlayerState(gameId);
                    return response.status() == 200 ? Optional.of(PlayerState.from(response.body())) : Optional.empty();
                },
                expected,
                description);
    }

    RoundState awaitRoundState(
            Actor actor, UUID gameId, int eraNumber, int roundNumber, Predicate<RoundState> expected) {
        return eventually(
                () -> {
                    var response = as(actor).getRoundStatus(gameId, eraNumber, roundNumber);
                    return response.status() == 200 ? Optional.of(RoundState.from(response.body())) : Optional.empty();
                },
                expected,
                "round " + eraNumber + "." + roundNumber + " transition");
    }

    ScoreBoard awaitScores(Actor actor, UUID gameId, Predicate<ScoreBoard> expected) {
        return eventually(
                () -> {
                    var response = as(actor).getScores(gameId);
                    return response.status() == 200 ? Optional.of(ScoreBoard.from(response.body())) : Optional.empty();
                },
                expected,
                "score publication");
    }

    GameHistory awaitGameHistory(Actor actor, UUID gameId, Predicate<GameHistory> expected, String description) {
        return eventually(
                () -> {
                    var response = as(actor).getGameHistory(gameId);
                    return response.status() == 200 ? Optional.of(GameHistory.from(response.body())) : Optional.empty();
                },
                expected,
                description);
    }

    private static <T> T eventually(Supplier<Optional<T>> observation, Predicate<T> expected, String description) {
        var observed = new AtomicReference<T>();
        await().atMost(TRANSITION_TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            var candidate = observation.get();
            assertThat(candidate).as(description).isPresent();
            assertThat(expected.test(candidate.orElseThrow())).as(description).isTrue();
            observed.set(candidate.orElseThrow());
        });
        return observed.get();
    }

    final class ActorActions {

        private final Actor actor;

        private ActorActions(Actor actor) {
            this.actor = actor;
        }

        JsonHttpClient.Response createLobby() {
            return http.post(gameUri("/lobbies"), actor, Map.of("playerName", actor.name()));
        }

        JsonHttpClient.Response joinLobby(UUID lobbyId) {
            return http.post(gameUri("/lobbies/" + lobbyId + "/join"), actor, Map.of("playerName", actor.name()));
        }

        JsonHttpClient.Response leaveLobby(UUID lobbyId) {
            return http.delete(gameUri("/lobbies/" + lobbyId + "/players/me"), actor);
        }

        JsonHttpClient.Response startGame(UUID lobbyId) {
            return http.post(gameUri("/lobbies/" + lobbyId + "/start"), actor, Map.of());
        }

        JsonHttpClient.Response getPlayerState(UUID gameId) {
            return http.get(readUri("/games/" + gameId + "/state"), actor);
        }

        JsonHttpClient.Response getScores(UUID gameId) {
            return http.get(gameUri("/games/" + gameId + "/scores"), actor);
        }

        JsonHttpClient.Response getGameHistory(UUID gameId) {
            return http.get(readUri("/games/" + gameId + "/history"), actor);
        }

        JsonHttpClient.Response getRoundStatus(UUID gameId, int eraNumber, int roundNumber) {
            return http.get(gameUri(roundPath(gameId, eraNumber, roundNumber) + "/status"), actor);
        }

        JsonHttpClient.Response playCard(
                UUID gameId,
                int eraNumber,
                int roundNumber,
                Card card,
                UUID targetEventId,
                UUID sourceOutcomeId,
                UUID targetOutcomeId) {
            var body = new LinkedHashMap<String, Object>();
            body.put("actionType", "CARD");
            body.put("cardInstanceId", card.cardInstanceId());
            body.put("targetEventId", targetEventId);
            if (sourceOutcomeId != null) {
                body.put("sourceOutcomeId", sourceOutcomeId);
            }
            body.put("targetOutcomeId", targetOutcomeId);
            return http.post(gameUri(roundPath(gameId, eraNumber, roundNumber) + "/actions"), actor, body);
        }

        JsonHttpClient.Response playSpecial(
                UUID gameId,
                int eraNumber,
                int roundNumber,
                String specialAction,
                UUID targetEventId,
                UUID targetOutcomeId) {
            var body = new LinkedHashMap<String, Object>();
            body.put("actionType", "SPECIAL");
            body.put("specialAction", specialAction);
            body.put("targetEventId", targetEventId);
            body.put("targetOutcomeId", targetOutcomeId);
            return http.post(gameUri(roundPath(gameId, eraNumber, roundNumber) + "/actions"), actor, body);
        }
    }

    record PlayerState(
            UUID gameId,
            int eraNumber,
            String phase,
            String myFaction,
            List<Card> hand,
            int myScore,
            List<ActiveEvent> activeEvents,
            List<PlayerView> players) {

        static PlayerState from(JsonNode body) {
            return new PlayerState(
                    uuid(body, "gameId"),
                    body.path("eraNumber").asInt(),
                    body.path("phase").asText(),
                    nullableText(body.get("myFaction")),
                    stream(body.path("myHand")).map(Card::from).toList(),
                    body.path("myScore").asInt(),
                    stream(body.path("activeEvents")).map(ActiveEvent::from).toList(),
                    stream(body.path("players")).map(PlayerView::from).toList());
        }
    }

    record Card(UUID cardInstanceId, String cardType) {

        static Card from(JsonNode body) {
            return new Card(uuid(body, "cardInstanceId"), body.path("cardType").asText());
        }
    }

    record ActiveEvent(UUID eventId, List<UUID> outcomeIds) {

        static ActiveEvent from(JsonNode body) {
            return new ActiveEvent(
                    uuid(body, "eventId"),
                    stream(body.path("outcomes"))
                            .map(outcome -> uuid(outcome, "outcomeId"))
                            .toList());
        }
    }

    record PlayerView(UUID playerId, int score, String faction) {

        static PlayerView from(JsonNode body) {
            return new PlayerView(
                    uuid(body, "playerId"), body.path("score").asInt(), nullableText(body.get("faction")));
        }
    }

    record RoundState(
            int eraNumber,
            int roundNumber,
            String status,
            int submittedCount,
            int totalPlayers,
            List<UUID> pendingPlayerIds) {

        static RoundState from(JsonNode body) {
            return new RoundState(
                    body.path("eraNumber").asInt(),
                    body.path("roundNumber").asInt(),
                    body.path("status").asText(),
                    body.path("submittedCount").asInt(),
                    body.path("totalPlayers").asInt(),
                    stream(body.path("pendingPlayerIds"))
                            .map(node -> UUID.fromString(node.asText()))
                            .toList());
        }
    }

    record ScoreBoard(int eraNumber, Map<UUID, Integer> scores) {

        static ScoreBoard from(JsonNode body) {
            var scores = new LinkedHashMap<UUID, Integer>();
            stream(body.path("scores"))
                    .forEach(score -> scores.put(
                            uuid(score, "playerId"), score.path("score").asInt()));
            return new ScoreBoard(body.path("eraNumber").asInt(), Map.copyOf(scores));
        }
    }

    record GameHistory(UUID gameId, List<EraHistory> eras) {

        static GameHistory from(JsonNode body) {
            return new GameHistory(
                    uuid(body, "gameId"),
                    stream(body.path("eras")).map(EraHistory::from).toList());
        }

        Optional<EraHistory> era(int eraNumber) {
            return eras.stream().filter(era -> era.eraNumber() == eraNumber).findFirst();
        }
    }

    record EraHistory(int eraNumber, List<DealtCard> myHand) {

        static EraHistory from(JsonNode body) {
            return new EraHistory(
                    body.path("eraNumber").asInt(),
                    stream(body.path("myHand")).map(DealtCard::from).toList());
        }
    }

    record DealtCard(UUID cardInstanceId, String cardType) {

        static DealtCard from(JsonNode body) {
            return new DealtCard(
                    uuid(body, "cardInstanceId"), body.path("cardType").asText());
        }
    }

    private static URI gameUri(String path) {
        return GAME_API.resolve(GAME_API.getPath() + path);
    }

    private static URI readUri(String path) {
        return READ_API.resolve(READ_API.getPath() + path);
    }

    private static String roundPath(UUID gameId, int eraNumber, int roundNumber) {
        return "/games/" + gameId + "/eras/" + eraNumber + "/rounds/" + roundNumber;
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).asText());
    }

    private static String nullableText(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
