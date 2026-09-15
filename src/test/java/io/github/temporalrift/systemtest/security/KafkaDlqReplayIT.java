package io.github.temporalrift.systemtest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.systemtest.replay.KafkaDlqReplay;

class KafkaDlqReplayIT {

    @Test
    void replaysOnlyTheSelectedGameWithOriginalKeyPayloadAndApplicationHeaders() throws Exception {
        var selectedGameId = UUID.randomUUID().toString();
        var otherGameId = UUID.randomUUID().toString();
        var eventId = UUID.randomUUID().toString();
        sendParkedRecord(selectedGameId, "selected-payload", eventId);
        sendParkedRecord(otherGameId, "other-payload", UUID.randomUUID().toString());

        assertThat(KafkaDlqReplay.replay(SecureKafka.adminProperties(), "game.events", selectedGameId))
                .isEqualTo(1);

        var consumerProperties = SecureKafka.adminProperties();
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-replay-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (var consumer = new KafkaConsumer<byte[], byte[]>(consumerProperties)) {
            consumer.subscribe(List.of("game.events"));
            var records = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]>>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
                assertThat(records).anySatisfy(consumerRecord -> {
                    assertThat(new String(consumerRecord.key(), StandardCharsets.UTF_8))
                            .isEqualTo(selectedGameId);
                    assertThat(new String(consumerRecord.value(), StandardCharsets.UTF_8))
                            .isEqualTo("selected-payload");
                    assertThat(headerValue(consumerRecord, "eventId")).isEqualTo(eventId);
                    assertThat(headerValue(consumerRecord, "kafka_dlt-exception-fqcn"))
                            .isNull();
                });
            });
            assertThat(records)
                    .noneMatch(consumerRecord ->
                            new String(consumerRecord.key(), StandardCharsets.UTF_8).equals(otherGameId));
        }
    }

    @Test
    void rejectsADeadLetterTopicAsAReplaySource() {
        var connectionProperties = SecureKafka.adminProperties();
        assertThatThrownBy(() -> KafkaDlqReplay.replay(connectionProperties, "game.events.dlq", "game"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported source topic");
    }

    private static void sendParkedRecord(String gameId, String payload, String eventId) throws Exception {
        var properties = SecureKafka.adminProperties();
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (var producer = new KafkaProducer<byte[], byte[]>(properties)) {
            var headers = new RecordHeaders();
            headers.add("gameId", gameId.getBytes(StandardCharsets.UTF_8));
            headers.add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
            headers.add("kafka_dlt-exception-fqcn", "java.lang.IllegalStateException".getBytes(StandardCharsets.UTF_8));
            producer.send(new ProducerRecord<>(
                            "game.events.dlq",
                            null,
                            gameId.getBytes(StandardCharsets.UTF_8),
                            payload.getBytes(StandardCharsets.UTF_8),
                            headers))
                    .get();
        }
    }

    private static String headerValue(
            org.apache.kafka.clients.consumer.ConsumerRecord<byte[], byte[]> consumerRecord, String key) {
        var header = consumerRecord.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
