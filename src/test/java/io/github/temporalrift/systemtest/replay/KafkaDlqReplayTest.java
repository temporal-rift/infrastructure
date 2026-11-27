package io.github.temporalrift.systemtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dead-letter replay argument parsing and record filtering, checked without any broker. */
class KafkaDlqReplayTest {

    @TempDir
    private Path temp;

    @Test
    void mainWithCompleteArgumentsReplaysNothingWhenTheDeadLetterTopicIsEmpty() throws Exception {
        var config = temp.resolve("command.properties");
        Files.writeString(config, "security.protocol=PLAINTEXT\n");
        var deadLetter = new TopicPartition("game.events.dlq", 0);
        var partitions = List.of(new PartitionInfo("game.events.dlq", 0, null, new Node[0], new Node[0]));

        try (var consumers = mockConstruction(KafkaConsumer.class, (mock, context) -> {
                    when(mock.partitionsFor("game.events.dlq")).thenReturn(partitions);
                    when(mock.endOffsets(any())).thenReturn(Map.of(deadLetter, 0L));
                    when(mock.position(deadLetter)).thenReturn(0L);
                });
                var producers = mockConstruction(KafkaProducer.class)) {
            KafkaDlqReplay.main(new String[] {
                "--bootstrap-server", "broker:9092",
                "--source-topic", "game.events",
                "--game-id", "game-1",
                "--command-config", config.toString()
            });

            var consumer = consumers.constructed().get(0);
            verify(consumer).assign(List.of(deadLetter));
            verify(producers.constructed().get(0)).flush();
        }
    }

    @Test
    void mainRejectsMissingRequiredOptions() {
        assertThatThrownBy(() -> KafkaDlqReplay.main(new String[] {"--bootstrap-server", "broker:9092"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--source-topic");
    }

    @Test
    void mainRejectsAnIncompletePair() {
        assertThatThrownBy(() -> KafkaDlqReplay.main(new String[] {"--bootstrap-server"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mainRejectsDuplicateAndUnknownOptions() {
        assertThatThrownBy(() -> KafkaDlqReplay.main(new String[] {
                    "--bootstrap-server", "a",
                    "--bootstrap-server", "b",
                    "--source-topic", "game.events",
                    "--game-id", "g"
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--bootstrap-server");
        assertThatThrownBy(() -> KafkaDlqReplay.main(new String[] {
                    "--bootstrap-server", "a",
                    "--source-topic", "game.events",
                    "--game-id", "g",
                    "--nope", "x"
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--nope");
    }

    @Test
    void mainRejectsUnsupportedSourceTopics() {
        assertThatThrownBy(() -> KafkaDlqReplay.main(
                        new String[] {"--bootstrap-server", "a", "--source-topic", "other.topic", "--game-id", "g"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other.topic");
    }

    @Test
    void belongsToGameMatchesKeysAndGameIdHeaders() {
        var byKey = new ConsumerRecord<>("game.events", 0, 0L, "game-1".getBytes(StandardCharsets.UTF_8), new byte[0]);
        var byHeader = new ConsumerRecord<byte[], byte[]>("game.events", 0, 0L, null, new byte[0]);
        byHeader.headers().add("gameId", "game-2".getBytes(StandardCharsets.UTF_8));
        var unrelated = new ConsumerRecord<byte[], byte[]>("game.events", 0, 0L, null, new byte[0]);

        assertThat(KafkaDlqReplay.belongsToGame(byKey, "game-1")).isTrue();
        assertThat(KafkaDlqReplay.belongsToGame(byHeader, "game-2")).isTrue();
        assertThat(KafkaDlqReplay.belongsToGame(unrelated, "game-1")).isFalse();
        assertThat(KafkaDlqReplay.belongsToGame(byKey, "other")).isFalse();
    }

    @Test
    void applicationHeadersDropDeadLetterDiagnosticsAndKeepApplicationHeaders() {
        var headers = new RecordHeaders()
                .add("gameId", "game-1".getBytes(StandardCharsets.UTF_8))
                .add("kafka_dlt-exception-fqcn", "boom".getBytes(StandardCharsets.UTF_8));

        var retained = KafkaDlqReplay.applicationHeaders(headers);

        assertThat(retained.headers("gameId")).hasSize(1);
        assertThat(retained.headers("kafka_dlt-exception-fqcn")).isEmpty();
    }
}
