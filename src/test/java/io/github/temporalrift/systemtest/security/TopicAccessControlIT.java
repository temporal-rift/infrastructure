package io.github.temporalrift.systemtest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/**
 * Proves the topology {@code compose.secure.yml} provisions for infrastructure#38: an authenticated,
 * least-privilege-authorized broker outside local development profiles. Runs against the standalone
 * {@code temporal-rift-secure} Compose project started by the {@code security-e2e} Maven profile — never
 * the local dev {@code compose.yml} or the {@code system-e2e} stack.
 */
class TopicAccessControlIT {

    @Test
    void retentionMatchesDocumentedClassPerTopic() throws Exception {
        var expectedRetentionMs = Map.of(
                "game.events", "604800000",
                "timeline.events", "604800000",
                "game.commands", "86400000",
                "game.dlq", "2592000000");

        try (var admin = AdminClient.create(SecureKafka.adminProperties())) {
            var resources = expectedRetentionMs.keySet().stream()
                    .map(topic -> new ConfigResource(ConfigResource.Type.TOPIC, topic))
                    .toList();
            Map<ConfigResource, Config> configs =
                    admin.describeConfigs(resources).all().get();

            for (var entry : expectedRetentionMs.entrySet()) {
                var resource = new ConfigResource(ConfigResource.Type.TOPIC, entry.getKey());
                var retentionMs = configs.get(resource).get("retention.ms").value();
                assertThat(retentionMs)
                        .as("retention.ms for %s", entry.getKey())
                        .isEqualTo(entry.getValue());
            }
        }
    }

    @Test
    void authorizedProduceAndConsumeSucceed() {
        var message = UUID.randomUUID().toString();

        try (var producer = producerFor(SecureKafka.gameServiceProperties())) {
            producer.send(new ProducerRecord<>("game.events", "key", message));
            producer.flush();
        }

        var consumerProperties = SecureKafka.readServiceProperties();
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "read-service.topic-access-control-it");
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = consumerFor(consumerProperties)) {
            consumer.subscribe(List.of("game.events"));
            var seen = new ArrayList<String>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> seen.add(record.value()));
                assertThat(seen).contains(message);
            });
        }
    }

    @Test
    void producingOutsideGrantedTopicSetIsDeniedAndLogged() throws Exception {
        // read-service has no producer anywhere in its own code, and no produce grant on any topic.
        try (var producer = producerFor(SecureKafka.readServiceProperties())) {
            var future = producer.send(new ProducerRecord<>("game.events", "key", "should-be-denied"));
            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(TopicAuthorizationException.class);
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var authorizerLog = KafkaAuthorizerLog.read();
            assertThat(authorizerLog)
                    .contains("Principal = User:read-service")
                    .contains("Denied")
                    .contains("Topic:LITERAL:game.events");
        });
    }

    @Test
    void consumingOutsideGrantedTopicSetIsDenied() {
        // timeline-service produces timeline.events and consumes game.events only -- it has no grant on
        // game.commands, which only game-service's PlayerReconnectKafkaConsumer is authorized to read.
        var consumerProperties = SecureKafka.timelineServiceProperties();
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "timeline-service.topic-access-control-it");
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = consumerFor(consumerProperties)) {
            consumer.subscribe(List.of("game.commands"));
            assertThatThrownBy(() -> consumer.poll(Duration.ofSeconds(10)))
                    .isInstanceOf(TopicAuthorizationException.class);
        }
    }

    private static KafkaProducer<String, String> producerFor(Properties properties) {
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        // Idempotence requires cluster-wide IDEMPOTENT_WRITE, which would otherwise surface a
        // ClusterAuthorizationException before the topic-level ACL check this test targets ever runs.
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        return new KafkaProducer<>(properties);
    }

    private static KafkaConsumer<String, String> consumerFor(Properties properties) {
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(properties);
    }
}
