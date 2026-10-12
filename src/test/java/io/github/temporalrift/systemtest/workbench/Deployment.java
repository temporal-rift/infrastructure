package io.github.temporalrift.systemtest.workbench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The non-secret descriptor {@code scripts/provision-workbench-e2e.sh} writes for the deployment under test. */
final class Deployment {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One isolated lane, as provisioned. */
    record Lane(
            String id,
            String variant,
            String project,
            String gameUrl,
            String timelineUrl,
            String readUrl,
            String kafkaBootstrap,
            String rulesDigest,
            String contentDigest,
            List<UUID> bots) {}

    private final JsonNode root;

    private Deployment(JsonNode root) {
        this.root = root;
    }

    static Deployment load() {
        var path = Path.of(System.getProperty("workbenchE2e.descriptor", "target/workbench-e2e/deployment.json"));
        try {
            return new Deployment(JSON.readTree(Files.readString(path)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read the deployment descriptor " + path, exception);
        }
    }

    static Deployment of(String json) {
        return new Deployment(JSON.readTree(json));
    }

    String runId() {
        return root.path("runId").asString();
    }

    String issuer() {
        return root.path("issuer").asString();
    }

    String workbenchUrl() {
        return root.path("workbenchUrl").asString();
    }

    String stackProject() {
        return root.path("workbenchProject").asString();
    }

    String ordinaryProject() {
        return root.path("ordinary").path("project").asString();
    }

    String ordinaryGameUrl() {
        return root.path("ordinary").path("gameUrl").asString();
    }

    String ordinaryTimelineUrl() {
        return root.path("ordinary").path("timelineUrl").asString();
    }

    int maxEras() {
        return root.path("maxEras").asInt();
    }

    JsonNode services() {
        return root.path("services");
    }

    JsonNode contracts() {
        return root.path("contracts");
    }

    JsonNode raw() {
        return root;
    }

    List<Lane> lanes() {
        var lanes = new ArrayList<Lane>();
        for (var lane : root.path("lanes")) {
            var bots = new ArrayList<UUID>();
            lane.path("bots").forEach(bot -> bots.add(UUID.fromString(bot.asString())));
            lanes.add(new Lane(
                    lane.path("id").asString(),
                    lane.path("variant").asString(),
                    lane.path("project").asString(),
                    lane.path("gameUrl").asString(),
                    lane.path("timelineUrl").asString(),
                    lane.path("readUrl").asString(),
                    lane.path("kafkaBootstrap").asString(),
                    lane.path("rulesDigest").asString(),
                    lane.path("contentDigest").asString(),
                    List.copyOf(bots)));
        }
        return List.copyOf(lanes);
    }

    List<Lane> lanes(String variant) {
        return lanes().stream().filter(lane -> lane.variant().equals(variant)).toList();
    }

    /** A lane of a variant; every lane of one variant must serve the same bundle digests. */
    Lane variantLane(String variant) {
        var variantLanes = lanes(variant);
        if (variantLanes.isEmpty()) {
            throw new IllegalStateException("No lane serves variant " + variant);
        }
        var first = variantLanes.getFirst();
        variantLanes.forEach(lane -> {
            if (!lane.rulesDigest().equals(first.rulesDigest())
                    || !lane.contentDigest().equals(first.contentDigest())) {
                throw new IllegalStateException("Lanes of variant " + variant + " serve different bundles");
            }
        });
        return first;
    }
}
