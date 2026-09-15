package io.github.temporalrift.systemtest.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

/**
 * Replays parked records for one game from a source-specific dead-letter topic.
 *
 * <p>The command intentionally leaves the parked records in place. Repeated runs are safe because source consumers
 * continue through their normal idempotency path.
 */
public final class KafkaDlqReplay {

    private static final Set<String> REPLAYABLE_SOURCE_TOPICS =
            Set.of("game.events", "timeline.events", "game.commands");
    private static final String DEAD_LETTER_HEADER_PREFIX = "kafka_dlt-";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);

    private KafkaDlqReplay() {}

    public static void main(String[] args) throws Exception {
        var arguments = Arguments.parse(args);
        var properties = arguments.connectionProperties();
        var replayed = replay(properties, arguments.sourceTopic(), arguments.gameId());
        System.out.printf(
                "Replayed %d record(s) for game %s to %s.%n", replayed, arguments.gameId(), arguments.sourceTopic());
    }

    /**
     * Replays matching records while preserving each source partition's order, key, payload, and application headers.
     * Dead-letter diagnostic headers are removed so a source consumer receives the original application record shape.
     */
    public static int replay(Properties connectionProperties, String sourceTopic, String gameId)
            throws InterruptedException, ExecutionException {
        validateSourceTopic(sourceTopic);
        var consumerProperties = new Properties();
        consumerProperties.putAll(connectionProperties);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        var producerProperties = new Properties();
        producerProperties.putAll(connectionProperties);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());

        try (var consumer = new KafkaConsumer<byte[], byte[]>(consumerProperties);
                var producer = new KafkaProducer<byte[], byte[]>(producerProperties)) {
            var deadLetterTopic = sourceTopic + ".dlq";
            var partitions = consumer.partitionsFor(deadLetterTopic).stream()
                    .map(partition -> new TopicPartition(deadLetterTopic, partition.partition()))
                    .sorted(Comparator.comparingInt(TopicPartition::partition))
                    .toList();
            var replayed = 0;
            for (var deadLetterPartition : partitions) {
                replayed += replayPartition(consumer, producer, deadLetterPartition, sourceTopic, gameId);
            }
            producer.flush();
            return replayed;
        }
    }

    static boolean belongsToGame(ConsumerRecord<byte[], byte[]> record, String gameId) {
        if (gameId.equals(utf8(record.key()))) {
            return true;
        }
        for (var header : record.headers().headers("gameId")) {
            if (gameId.equals(utf8(header.value()))) {
                return true;
            }
        }
        return false;
    }

    static RecordHeaders applicationHeaders(Iterable<Header> headers) {
        var retained = new RecordHeaders();
        headers.forEach(header -> {
            if (!header.key().startsWith(DEAD_LETTER_HEADER_PREFIX)) {
                retained.add(header);
            }
        });
        return retained;
    }

    private static int replayPartition(
            KafkaConsumer<byte[], byte[]> consumer,
            KafkaProducer<byte[], byte[]> producer,
            TopicPartition deadLetterPartition,
            String sourceTopic,
            String gameId)
            throws InterruptedException, ExecutionException {
        consumer.assign(List.of(deadLetterPartition));
        consumer.seekToBeginning(List.of(deadLetterPartition));
        var endOffset = consumer.endOffsets(List.of(deadLetterPartition)).get(deadLetterPartition);
        var deliveries = new ArrayList<java.util.concurrent.Future<?>>();
        while (consumer.position(deadLetterPartition) < endOffset) {
            for (var record : consumer.poll(POLL_TIMEOUT).records(deadLetterPartition)) {
                if (belongsToGame(record, gameId)) {
                    deliveries.add(producer.send(new ProducerRecord<>(
                            sourceTopic,
                            record.partition(),
                            record.timestamp(),
                            record.key(),
                            record.value(),
                            applicationHeaders(record.headers()))));
                }
            }
        }
        for (var delivery : deliveries) {
            delivery.get();
        }
        return deliveries.size();
    }

    private static void validateSourceTopic(String sourceTopic) {
        if (!REPLAYABLE_SOURCE_TOPICS.contains(sourceTopic)) {
            throw new IllegalArgumentException("Unsupported source topic '%s'. Choose one of: %s"
                    .formatted(sourceTopic, REPLAYABLE_SOURCE_TOPICS));
        }
    }

    private static String utf8(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private record Arguments(String bootstrapServer, String sourceTopic, String gameId, String commandConfig) {

        static Arguments parse(String[] args) {
            var values = new java.util.HashMap<String, String>();
            for (var index = 0; index < args.length; index += 2) {
                if (index + 1 >= args.length || !args[index].startsWith("--")) {
                    throw new IllegalArgumentException("Arguments must be supplied as --name value pairs.");
                }
                var option = args[index].substring(2);
                if (!Set.of("bootstrap-server", "source-topic", "game-id", "command-config")
                                .contains(option)
                        || values.put(option, args[index + 1]) != null) {
                    throw new IllegalArgumentException("Unsupported or duplicate option: --" + option);
                }
            }
            for (var required : List.of("bootstrap-server", "source-topic", "game-id")) {
                if (!values.containsKey(required) || values.get(required).isBlank()) {
                    throw new IllegalArgumentException("Missing required option: --" + required);
                }
            }
            validateSourceTopic(values.get("source-topic"));
            return new Arguments(
                    values.get("bootstrap-server"),
                    values.get("source-topic"),
                    values.get("game-id"),
                    values.get("command-config"));
        }

        Properties connectionProperties() throws java.io.IOException {
            var properties = new Properties();
            properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
            if (commandConfig != null) {
                try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(commandConfig))) {
                    properties.load(input);
                }
            }
            return properties;
        }
    }
}
