package io.github.temporalrift.systemtest.workbench;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * Delivers every event record a lane publishes a second time, unchanged, to the same topic and partition,
 * as an at-least-once broker may. Re-delivered copies carry a marker header so they are not duplicated again.
 */
final class KafkaDuplicator implements AutoCloseable {

    private static final String MARKER = "wbe2e-redelivery";
    private static final List<String> TOPICS = List.of("game.events", "timeline.events");

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger redelivered = new AtomicInteger();
    private final Thread thread;

    KafkaDuplicator(String bootstrap) {
        thread = Thread.ofPlatform()
                .daemon()
                .name("kafka-duplicator-" + bootstrap)
                .start(() -> run(bootstrap));
    }

    int redelivered() {
        return redelivered.get();
    }

    private void run(String bootstrap) {
        var consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "wbe2e-duplicator-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        try (var consumer = new KafkaConsumer<byte[], byte[]>(consumerProperties);
                var producer = new KafkaProducer<byte[], byte[]>(producerProperties)) {
            consumer.subscribe(TOPICS);
            while (running.get()) {
                for (var event : consumer.poll(Duration.ofMillis(250))) {
                    if (event.headers().lastHeader(MARKER) != null) {
                        continue;
                    }
                    var copy = new ProducerRecord<>(
                            event.topic(), event.partition(), event.key(), event.value(), event.headers());
                    copy.headers().add(MARKER, new byte[] {1});
                    producer.send(copy);
                    redelivered.incrementAndGet();
                }
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        running.set(false);
        thread.join(10_000);
    }
}
