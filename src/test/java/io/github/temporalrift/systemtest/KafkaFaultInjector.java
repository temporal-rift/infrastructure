package io.github.temporalrift.systemtest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes synthetic {@code ProbabilityStateRevealed} facts directly onto {@code timeline.events}, using the
 * header-only envelope convention -- no JSON wrapper, envelope metadata (eventType, eventId, aggregateId,
 * aggregateType, gameId, occurredAt, version) travels as Kafka headers and the event body is the payload.
 *
 * <p>Scoped strictly to the two fault-injection assertions this change needs -- replayed delivery and delayed
 * cross-era delivery, a deliberate design choice made because neither can be reproduced through REST alone (no
 * client can force broker replay or cross-topic reordering). This is never used to shortcut or fabricate the
 * ordinary Scan reveal path; every other assertion goes through the real REST → game-service → Kafka →
 * timeline-service → Kafka → read-service → REST round trip. Real published facts are read by
 * {@link KafkaEventProbe}, not this class.
 */
final class KafkaFaultInjector implements AutoCloseable {

    private static final String TIMELINE_EVENTS_TOPIC = "timeline.events";
    private static final String BOOTSTRAP_SERVERS = "localhost:19092";

    private final KafkaProducer<String, byte[]> producer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    KafkaFaultInjector() {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        producer = new KafkaProducer<>(properties);
    }

    /**
     * Publishes a synthetic {@code ProbabilityStateRevealed} for one event, addressed to a single viewer, with a
     * caller-supplied envelope {@code eventId} (message identity, distinct from the scanned event's own
     * {@code eventId} in the payload). A replay test publishes the identical {@code envelopeEventId} twice to
     * prove eventId-idempotency; a delayed cross-era test uses a fresh {@code envelopeEventId} with a
     * prior-era {@code eraNumber} so idempotency does not itself suppress the delivery being tested.
     */
    void publishProbabilityStateRevealed(
            UUID envelopeEventId,
            UUID gameId,
            int eraNumber,
            int roundNumber,
            UUID viewerPlayerId,
            UUID eventId,
            List<Map<String, Object>> outcomes) {
        var payload = Map.of(
                "gameId",
                gameId.toString(),
                "eraNumber",
                eraNumber,
                "roundNumber",
                roundNumber,
                "playerId",
                viewerPlayerId.toString(),
                "eventId",
                eventId.toString(),
                "outcomes",
                outcomes);

        var producerRecord = new ProducerRecord<String, byte[]>(
                TIMELINE_EVENTS_TOPIC, gameId.toString(), objectMapper.writeValueAsBytes(payload));
        producerRecord
                .headers()
                .add("eventType", "ProbabilityStateRevealed".getBytes(StandardCharsets.UTF_8))
                .add("eventId", envelopeEventId.toString().getBytes(StandardCharsets.UTF_8))
                .add("aggregateId", eventId.toString().getBytes(StandardCharsets.UTF_8))
                .add("aggregateType", "FutureEvent".getBytes(StandardCharsets.UTF_8))
                .add("gameId", gameId.toString().getBytes(StandardCharsets.UTF_8))
                .add("occurredAt", Instant.now().toString().getBytes(StandardCharsets.UTF_8))
                .add("version", "1".getBytes(StandardCharsets.UTF_8));

        try {
            // Blocks for the broker acknowledgement so a subsequent REST poll never races the publish itself.
            producer.send(producerRecord).get();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to publish synthetic ProbabilityStateRevealed", exception);
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
