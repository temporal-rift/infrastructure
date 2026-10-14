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

    static ObjectNode build(Deployment deployment, Workbench.Catalog catalog, Spec spec) {
        var manifest = JSON.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("name", spec.name());
        var variants = manifest.putArray("variants");
        JsonNode provenance = null;
        for (var label : spec.variants()) {
            var lane = deployment.variantLane(label);
            var bundle = servedBundle(catalog.laneBundles(), lane);
            var variant = variants.addObject();
            variant.put("label", label);
            variant.set("rulesArtifact", bundle.path("rulesArtifact").deepCopy());
            variant.set("contentArtifact", bundle.path("contentArtifact").deepCopy());
            variant.put("effectiveRulesDigest", lane.rulesDigest());
            variant.put("effectiveContentDigest", lane.contentDigest());
            var served = JSON.createObjectNode();
            served.set("services", bundle.path("services"));
            served.set("contracts", bundle.path("contracts"));
            if (provenance != null && !provenance.equals(served)) {
                throw new IllegalStateException("The served bundles of the variants declare different provenance");
            }
            provenance = served;
        }
        if (provenance == null) {
            throw new IllegalStateException("A manifest needs at least one variant");
        }
        manifest.set("services", provenance.path("services").deepCopy());
        manifest.set("contracts", provenance.path("contracts").deepCopy());
        var policies = manifest.putArray("policies");
        for (var policyId : spec.policyIds()) {
            var bundle = policy(catalog.policies(), policyId);
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

    /** The bundle the workbench lists for the digests a lane serves. */
    private static JsonNode servedBundle(JsonNode bundles, Deployment.Lane lane) {
        for (var bundle : bundles) {
            if (bundle.path("effectiveRulesDigest").asString().equals(lane.rulesDigest())
                    && bundle.path("effectiveContentDigest").asString().equals(lane.contentDigest())) {
                return bundle;
            }
        }
        throw new IllegalStateException("The workbench does not list the bundle lane " + lane.id() + " serves");
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
