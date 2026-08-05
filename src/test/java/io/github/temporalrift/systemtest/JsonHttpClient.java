package io.github.temporalrift.systemtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class JsonHttpClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    Response get(URI uri, Actor actor) {
        return exchange(request(uri, actor).GET().build());
    }

    Response post(URI uri, Actor actor, Object body) {
        return exchange(request(uri, actor)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                .build());
    }

    Response delete(URI uri, Actor actor) {
        return exchange(request(uri, actor).DELETE().build());
    }

    private static HttpRequest.Builder request(URI uri, Actor actor) {
        var builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Accept", "application/json");
        if (actor != null) {
            builder.header("Authorization", "Bearer " + actor.token());
        }
        return builder;
    }

    private Response exchange(HttpRequest request) {
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            var rawBody = response.body();
            JsonNode body = rawBody == null || rawBody.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(rawBody);
            return new Response(response.statusCode(), body, rawBody);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "HTTP request failed: " + request.method() + " " + request.uri(), exception);
        }
    }

    record Response(int status, JsonNode body, String rawBody) {

        Response assertStatus(int expected) {
            assertThat(status).as("HTTP response body: %s", rawBody).isEqualTo(expected);
            return this;
        }
    }
}
