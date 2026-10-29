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

    String ordinaryReadUrl() {
        return root.path("ordinary").path("readUrl").asString();
    }

    int maxEras() {
        return root.path("maxEras").asInt();
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

    /** The lane every lane equals: all lanes serve the baseline bundle, so they must agree on its digests. */
    Lane baselineLane() {
        var all = lanes();
        if (all.isEmpty()) {
            throw new IllegalStateException("The deployment has no lane");
        }
        var first = all.getFirst();
        all.forEach(lane -> {
            if (!lane.rulesDigest().equals(first.rulesDigest())
                    || !lane.contentDigest().equals(first.contentDigest())) {
                throw new IllegalStateException("Lanes serve different bundles");
            }
        });
        return first;
    }
}
