package io.github.temporalrift.systemtest.workbench;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Minimal blocking HTTP used by every harness client. */
final class Http {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private Http() {}

    /** A response; {@code status} is -1 when no response arrived at all (for example while a service restarts). */
    record Response(int status, String body) {

        JsonNode json() {
            return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        }

        String code() {
            return json().path("code").asString();
        }

        Response requireStatus(int expected) {
            if (status != expected) {
                throw new AssertionError("Expected HTTP " + expected + " but got " + status + ": " + body);
            }
            return this;
        }
    }

    static Response send(String method, String url, String token, String body, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        if (body != null && !headers.containsKey("Content-Type")) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(
                method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            var response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException exception) {
            return new Response(-1, exception.toString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted calling " + method + " " + url, exception);
        }
    }

    static Response get(String url, String token) {
        return send("GET", url, token, null, Map.of("Accept", "application/json"));
    }

    static String form(Map<String, String> fields) {
        var parts = new java.util.ArrayList<String>();
        fields.forEach((name, value) -> parts.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return String.join("&", parts);
    }
}
