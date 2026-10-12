package io.github.temporalrift.systemtest.workbench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Collects the versioned evidence of one suite and writes it as a single JSON document. Evidence holds
 * versions, digests, counts and measurements; credentials never reach it.
 */
final class Evidence {

    /** Version of this document layout. */
    static final int FORMAT_VERSION = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String suite;
    private final Map<String, Object> sections = new ConcurrentHashMap<>();

    Evidence(String suite) {
        this.suite = suite;
    }

    /** Records a named section: any value Jackson can serialize. */
    void put(String section, Object value) {
        sections.put(section, value);
    }

    ObjectNode document(Deployment deployment) {
        var document = JSON.createObjectNode();
        document.put("formatVersion", FORMAT_VERSION);
        document.put("suite", suite);
        document.put("runId", deployment.runId());
        document.put("recordedAt", Instant.now().toString());
        document.put("timingMode", "LOGICAL");
        document.put("maxEras", deployment.maxEras());
        document.set("services", deployment.services());
        document.set("contracts", deployment.contracts());
        var lanes = document.putArray("lanes");
        deployment.lanes().forEach(lane -> {
            var node = lanes.addObject();
            node.put("id", lane.id());
            node.put("variant", lane.variant());
            node.put("rulesDigest", lane.rulesDigest());
            node.put("contentDigest", lane.contentDigest());
        });
        new TreeMap<>(sections).forEach((name, value) -> document.set(name, JSON.valueToTree(value)));
        return document;
    }

    void write(Deployment deployment) {
        var directory = Path.of(System.getProperty("workbenchE2e.evidenceDir", "target/workbench-e2e-evidence"));
        try {
            Files.createDirectories(directory);
            Files.writeString(
                    directory.resolve(suite + ".json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document(deployment)));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write the " + suite + " evidence", exception);
        }
    }
}
