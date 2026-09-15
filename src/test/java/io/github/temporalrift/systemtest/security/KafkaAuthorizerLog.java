package io.github.temporalrift.systemtest.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * {@code StandardAuthorizer} denials are logged by the {@code kafka.authorizer.logger}, which the broker
 * image's own default {@code log4j.properties} routes to {@code kafka-authorizer.log} — a separate file
 * from the broker's stdout, so {@code docker compose logs} never shows it. Confirmed by actually running
 * {@code compose.secure.yml} while authoring this test.
 */
final class KafkaAuthorizerLog {

    private KafkaAuthorizerLog() {}

    static String read() throws IOException, InterruptedException {
        var process = new ProcessBuilder(
                        "docker",
                        "compose",
                        "-f",
                        "compose.secure.yml",
                        "exec",
                        "-T",
                        "kafka-secure",
                        "cat",
                        "/opt/kafka/logs/kafka-authorizer.log")
                .redirectErrorStream(true)
                .start();
        String output;
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        process.waitFor(10, TimeUnit.SECONDS);
        return output;
    }
}
