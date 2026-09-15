package io.github.temporalrift.systemtest.security;

import java.util.Properties;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;

/**
 * Connection settings for {@code compose.secure.yml}'s standalone broker, and the SASL/PLAIN credentials
 * {@code provision-kafka-acls.sh} grants to each service identity there. These are local/CI-only demo
 * passwords that exist only in this stack — see compose.secure.yml for why that's an acceptable choice for
 * this demonstration.
 */
final class SecureKafka {

    static final String BOOTSTRAP_SERVERS = "localhost:39092";

    private SecureKafka() {}

    static Properties clientProperties(String principal, String password) {
        var properties = new Properties();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SASL_PLAINTEXT.name);
        properties.put(SaslConfigs.SASL_MECHANISM, "PLAIN");
        properties.put(
                SaslConfigs.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";"
                        .formatted(principal, password));
        return properties;
    }

    static Properties adminProperties() {
        return clientProperties("admin", "admin-local-demo-secret");
    }

    static Properties gameServiceProperties() {
        return clientProperties("game-service", "game-service-local-demo-secret");
    }

    static Properties timelineServiceProperties() {
        return clientProperties("timeline-service", "timeline-service-local-demo-secret");
    }

    static Properties readServiceProperties() {
        return clientProperties("read-service", "read-service-local-demo-secret");
    }
}
