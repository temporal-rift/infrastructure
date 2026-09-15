package io.github.temporalrift.systemtest.security;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * {@code StandardAuthorizer} denials are logged by the {@code kafka.authorizer.logger}, which the broker
 * image's own default {@code log4j.properties} routes to {@code kafka-authorizer.log} — a separate file
 * from the broker's stdout, so {@code docker compose logs} never shows it. Confirmed by actually running
 * {@code compose.secure.yml} while authoring this test.
 */
final class KafkaAuthorizerLog {

    private KafkaAuthorizerLog() {}

    static String read() throws IOException, InterruptedException {
        // Output is redirected straight to a file rather than read from the process's stdout pipe: a pipe
        // read blocks until EOF, so if `docker compose exec` ever hung with stdout still open, that call
        // would block indefinitely -- reached before the timeout below is ever checked, and unbounded even
        // though this method's caller waits inside an Awaitility timeout, since a synchronous blocking read
        // in that same lambda can't be preempted by it.
        var outputFile = Files.createTempFile("kafka-authorizer-log-", ".txt");
        try {
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
                    .redirectOutput(outputFile.toFile())
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "docker compose exec cat kafka-authorizer.log did not finish within 10 seconds");
            }
            return Files.readString(outputFile);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }
}
