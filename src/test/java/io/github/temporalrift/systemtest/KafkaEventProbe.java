package io.github.temporalrift.systemtest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Passively observes real facts published on {@code timeline.events} -- specifically
 * {@code BandedProbabilityPublished}, which read-service's notification module only ever pushes over WebSocket
 * and never persists to a REST-queryable projection (confirmed by inspecting the projection and notification
 * modules directly -- no REST surface exists for it). Kafka is a declared service-to-service boundary for this
 * black-box system-integration test (unlike a browser/client E2E, which would be scoped to REST/WebSocket only),
 * so reading the real publication here observes the canonical public-band fact the running system actually
 * produced, rather than fabricating one.
 *
 * <p>One instance owns one consumer group for its whole lifecycle -- construct it once per scenario (before the
 * round whose publication it needs to observe closes, so partition assignment is already settled and no message
 * can be missed), reuse it for every {@link #awaitBandPublication} call, then {@link #close()} it. Never
 * publishes anything -- see {@link KafkaFaultInjector} for the separate, narrowly-scoped synthetic producer used
 * only by the replay/delayed-delivery fault-injection scenarios.
 */
final class KafkaEventProbe implements AutoCloseable {

    private static final String TIMELINE_EVENTS_TOPIC = "timeline.events";
    private static final String BOOTSTRAP_SERVERS = "localhost:19092";

    private final KafkaConsumer<String, byte[]> consumer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    KafkaEventProbe() {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-band-probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumer = new KafkaConsumer<>(properties);
        consumer.subscribe(List.of(TIMELINE_EVENTS_TOPIC));

        // Block until the group's partitions are actually assigned, not just subscribed, so the caller can rely
        // on "constructed" meaning "will not miss a publication from this point on" regardless of offset policy.
        var deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (consumer.assignment().isEmpty() && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(200));
        }
        if (consumer.assignment().isEmpty()) {
            throw new IllegalStateException(
                    "KafkaEventProbe never received a partition assignment for " + TIMELINE_EVENTS_TOPIC);
        }
    }

    /**
     * Polls this probe's already-assigned consumer for the real {@code BandedProbabilityPublished} publication
     * of one game/era, returned whole (every event's every outcome) so the caller can compare every covered
     * event/outcome, not just one. The topic is shared across every scenario in the same e2e run, so matching
     * on {@code gameId}/{@code eraNumber} (not just {@code eventType}) is what scopes this to the caller's own
     * fact and rejects any unrelated event.
     */
    Optional<JsonNode> awaitBandPublication(UUID gameId, int eraNumber, Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (var consumerRecord : consumer.poll(Duration.ofMillis(500))) {
                if (!"BandedProbabilityPublished".equals(header(consumerRecord.headers(), "eventType"))
                        || !gameId.toString().equals(header(consumerRecord.headers(), "gameId"))) {
                    continue;
                }
                var payload = objectMapper.readTree(consumerRecord.value());
                if (payload.path("eraNumber").asInt() == eraNumber) {
                    return Optional.of(payload);
                }
            }
        }
        return Optional.empty();
    }

    /** Extracts one (event, outcome)'s band from a publication returned by {@link #awaitBandPublication}. */
    static Optional<String> bandFor(JsonNode publication, UUID eventId, UUID outcomeId) {
        for (JsonNode eventState : publication.path("eventStates")) {
            if (!eventId.toString().equals(eventState.path("eventId").asText())) {
                continue;
            }
            for (JsonNode outcome : eventState.path("outcomes")) {
                if (outcomeId.toString().equals(outcome.path("outcomeId").asText())) {
                    return Optional.of(outcome.path("band").asText());
                }
            }
        }
        return Optional.empty();
    }

    private static String header(Headers headers, String key) {
        var header = headers.lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        consumer.close();
    }
}
