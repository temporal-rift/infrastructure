package io.github.temporalrift.systemtest;

/** Shared connection settings for the e2e-only Kafka listener exposed by {@code compose.e2e.yml}. */
final class E2eKafka {

    static final String BOOTSTRAP_SERVERS = "localhost:19092";

    private E2eKafka() {}
}
