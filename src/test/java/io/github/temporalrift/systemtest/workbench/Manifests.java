package io.github.temporalrift.systemtest.workbench;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds experiment manifests that cite exactly what the provisioned lanes serve. */
final class Manifests {

    static final List<String> FACTIONS = List.of("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS");
    static final String BASELINE = "baseline";
    static final String CANDIDATE = "candidate";
    static final String RANDOM = "random-v1";
    static final String GREEDY = "faction-greedy-v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private Manifests() {}

    /** What a manifest holds besides the deployment's own provenance. */
    record Spec(
            String name,
            List<String> variants,
            List<String> seeds,
            List<Integer> playerCounts,
            List<List<String>> factionSets,
            List<String> policyIds,
            int concurrency,
            int caseWallTimeoutSeconds) {}

    /** All 10 three-faction, 5 four-faction and 1 five-faction sets, in a stable order. */
    static List<List<String>> fullFactionSets() {
        var sets = new ArrayList<List<String>>();
        sets.addAll(combinations(3));
        sets.addAll(combinations(4));
        sets.addAll(combinations(5));
        return List.copyOf(sets);
    }

    static List<List<String>> combinations(int size) {
        var result = new ArrayList<List<String>>();
        combine(size, 0, new ArrayList<>(), result);
        return result;
    }

    private static void combine(int size, int start, List<String> current, List<List<String>> result) {
        if (current.size() == size) {
            result.add(List.copyOf(current));
            return;
        }
        for (var index = start; index < FACTIONS.size(); index++) {
            current.add(FACTIONS.get(index));
            combine(size, index + 1, current, result);
            current.removeLast();
        }
    }

    static ObjectNode build(Deployment deployment, JsonNode policyCatalog, Spec spec) {
        var manifest = JSON.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("name", spec.name());
        var variants = manifest.putArray("variants");
        for (var label : spec.variants()) {
            var lane = deployment.variantLane(label);
            var variant = variants.addObject();
            variant.put("label", label);
            variant.putObject("rulesArtifact").put("digest", lane.rulesDigest());
            variant.putObject("contentArtifact").put("digest", lane.contentDigest());
            variant.put("effectiveRulesDigest", lane.rulesDigest());
            variant.put("effectiveContentDigest", lane.contentDigest());
        }
        manifest.set("services", deployment.services().deepCopy());
        manifest.set("contracts", deployment.contracts().deepCopy());
        var policies = manifest.putArray("policies");
        for (var policyId : spec.policyIds()) {
            var bundle = policy(policyCatalog, policyId);
            var policy = policies.addObject();
            policy.put("id", bundle.path("id").asString());
            policy.put("version", bundle.path("version").asString());
            policy.put("artifactDigest", bundle.path("artifactDigest").asString());
            policy.putObject("parameters");
            var seat = policy.putArray("seatAssignment").addObject();
            seat.put("seatIndex", 0);
            seat.put("policyId", bundle.path("id").asString());
            seat.put("policyVersion", bundle.path("version").asString());
        }
        var seeds = manifest.putArray("seeds");
        spec.seeds().forEach(seeds::add);
        var counts = manifest.putArray("playerCounts");
        spec.playerCounts().forEach(counts::add);
        var sets = manifest.putArray("factionSets");
        spec.factionSets().forEach(set -> {
            var node = sets.addArray();
            set.forEach(node::add);
        });
        manifest.put("seatRotationMode", "CYCLIC");
        manifest.put("timingMode", "LOGICAL");
        manifest.put("concurrency", spec.concurrency());
        manifest.put("caseWallTimeoutSeconds", spec.caseWallTimeoutSeconds());
        manifest.put("maxRejectedCandidatesPerWindow", 10);
        return manifest;
    }

    private static JsonNode policy(JsonNode catalog, String id) {
        for (var bundle : catalog) {
            if (bundle.path("id").asString().equals(id)) {
                return bundle;
            }
        }
        throw new IllegalStateException("The workbench does not offer policy " + id);
    }
}
