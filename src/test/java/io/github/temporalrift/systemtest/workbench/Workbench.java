package io.github.temporalrift.systemtest.workbench;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;

/** The workbench's generated HTTP interface, called with one identity's bearer token. */
final class Workbench {

    private static final int PAGE = 500;

    private final String baseUrl;
    private final String token;

    Workbench(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    Http.Response get(String path) {
        return Http.get(baseUrl + path, token);
    }

    Http.Response getAs(String path, String accept) {
        return Http.send("GET", baseUrl + path, token, null, Map.of("Accept", accept));
    }

    Http.Response post(String path, JsonNode body, UUID idempotencyKey) {
        var headers = idempotencyKey == null
                ? Map.of("Accept", "application/json")
                : Map.of("Accept", "application/json", "Idempotency-Key", idempotencyKey.toString());
        return Http.send("POST", baseUrl + path, token, body == null ? "{}" : body.toString(), headers);
    }

    JsonNode policies() {
        return get("/api/v1/policies").requireStatus(200).json().path("items");
    }

    JsonNode laneBundles() {
        return get("/api/v1/lane-bundles").requireStatus(200).json().path("items");
    }

    /** Applies and validates a variant's overrides on a baseline rules document without persisting anything. */
    @FunctionalInterface
    interface VariantRules {
        JsonNode preview(JsonNode rulesArtifact, int parameterSpaceVersion, Map<String, Number> overrides);
    }

    /** What the workbench offers a designer to cite in a manifest. */
    record Catalog(JsonNode policies, JsonNode laneBundles, VariantRules variantRules) {

        Catalog(JsonNode policies, JsonNode laneBundles) {
            this(policies, laneBundles, (rulesArtifact, version, overrides) -> {
                throw new UnsupportedOperationException("This catalog previews no variant rules");
            });
        }
    }

    Catalog catalog() {
        return new Catalog(policies(), laneBundles(), this::previewVariantRules);
    }

    /** The rules, their digest and the normalised overrides that freezing a variant would store. */
    JsonNode previewVariantRules(JsonNode rulesArtifact, int parameterSpaceVersion, Map<String, Number> overrides) {
        var body = Http.JSON.createObjectNode();
        body.set("rulesArtifact", rulesArtifact.deepCopy());
        body.put("parameterSpaceVersion", parameterSpaceVersion);
        var node = body.putObject("overrides");
        overrides.forEach((key, value) -> node.put(key, value.doubleValue()));
        return post("/api/v1/variant-rules-previews", body, null)
                .requireStatus(200)
                .json();
    }

    JsonNode createExperiment(JsonNode manifest, UUID key) {
        return post("/api/v1/experiments", manifest, key).requireStatus(201).json();
    }

    JsonNode startRun(String experimentId, UUID key) {
        return post("/api/v1/experiments/" + experimentId + "/runs", null, key)
                .requireStatus(202)
                .json();
    }

    Http.Response cancel(String runId, UUID key) {
        return post("/api/v1/runs/" + runId + "/cancel", null, key);
    }

    Http.Response resume(String runId, UUID key) {
        return post("/api/v1/runs/" + runId + "/resume", null, key);
    }

    JsonNode run(String runId) {
        return get("/api/v1/runs/" + runId).requireStatus(200).json();
    }

    /**
     * Polls a run until the predicate holds. Transport failures and server errors (for example a restart) are retried;
     * a client error means the run cannot appear, so it fails at once.
     */
    JsonNode awaitRun(String runId, Predicate<JsonNode> condition, Duration timeout, String description) {
        Objects.requireNonNull(runId, "No run to await: the test that launches it did not succeed");
        var latest = new JsonNode[1];
        await(description).atMost(timeout).pollInterval(Duration.ofSeconds(5)).until(() -> {
            var response = get("/api/v1/runs/" + runId);
            if (response.status() != 200) {
                return retryable(response);
            }
            latest[0] = response.json();
            return condition.test(latest[0]);
        });
        return latest[0];
    }

    /** A poll that treats a failed call (a restarting service, a transient error) as "not yet". */
    static boolean orFalse(java.util.function.BooleanSupplier poll) {
        try {
            return poll.getAsBoolean();
        } catch (RuntimeException | AssertionError _) {
            return false;
        }
    }

    static boolean isFinal(JsonNode run) {
        return switch (run.path("state").asString()) {
            case "COMPLETED", "CANCELLED", "FAILED" -> true;
            default -> false;
        };
    }

    /** Every case summary of a run, in matrix order, optionally restricted by state and variant. */
    List<JsonNode> caseSummaries(String runId, String state, String variant) {
        var all = new ArrayList<JsonNode>();
        for (var offset = 0; ; offset += PAGE) {
            var query = "?limit=" + PAGE + "&offset=" + offset + (state == null ? "" : "&state=" + state)
                    + (variant == null ? "" : "&variantLabel=" + variant);
            var page = get("/api/v1/runs/" + runId + "/cases" + query)
                    .requireStatus(200)
                    .json();
            page.path("items").forEach(all::add);
            if (offset + PAGE >= page.path("total").asInt()) {
                return all;
            }
        }
    }

    JsonNode caseDetail(String runId, String caseId) {
        return get("/api/v1/runs/" + runId + "/cases/" + caseId)
                .requireStatus(200)
                .json();
    }

    /** Pages through a whole replay and returns its steps. */
    List<JsonNode> replay(String runId, String caseId, String perspective, Integer seatIndex) {
        var steps = new ArrayList<JsonNode>();
        Integer after = null;
        do {
            var query = "?perspective=" + perspective + (seatIndex == null ? "" : "&seatIndex=" + seatIndex) + "&limit="
                    + PAGE + (after == null ? "" : "&afterStep=" + after);
            var page = get("/api/v1/runs/" + runId + "/cases/" + caseId + "/replay" + query)
                    .requireStatus(200)
                    .json();
            page.path("steps").forEach(steps::add);
            var next = page.path("nextStep");
            after = next.isNumber() ? next.asInt() : null;
        } while (after != null);
        return steps;
    }

    Http.Response reproduce(String runId, String caseId, UUID key) {
        return post("/api/v1/runs/" + runId + "/cases/" + caseId + "/reproductions", null, key);
    }

    /**
     * Repeats the reproduction request, which returns the same reproduction in whatever state it reached. Like
     * {@link #awaitRun}, it retries transport failures and server errors and fails at once on a client error.
     */
    JsonNode awaitReproduction(String runId, String caseId, UUID key, Duration timeout) {
        Objects.requireNonNull(runId, "No run to reproduce from: the test that launches it did not succeed");
        Objects.requireNonNull(caseId, "No case to reproduce: the test that selects it did not succeed");
        var latest = new JsonNode[1];
        await("reproduction of case " + caseId + " to finish")
                .atMost(timeout)
                .pollInterval(Duration.ofSeconds(10))
                .until(() -> {
                    var response = reproduce(runId, caseId, key);
                    if (response.status() != 202) {
                        return retryable(response);
                    }
                    latest[0] = response.json();
                    return switch (latest[0].path("state").asString()) {
                        case "MATCH", "DIVERGED", "FAILED" -> true;
                        default -> false;
                    };
                });
        return latest[0];
    }

    /** Not yet: a transport failure (status -1) or a server error. A client error is final, so it fails the wait. */
    static boolean retryable(Http.Response response) {
        if (response.status() >= 400 && response.status() < 500) {
            throw new AssertionError("HTTP " + response.status() + " cannot resolve by waiting: " + response.body());
        }
        return false;
    }
}
