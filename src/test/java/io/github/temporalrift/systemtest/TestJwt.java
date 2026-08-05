package io.github.temporalrift.systemtest;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

final class TestJwt {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final PrivateKey PRIVATE_KEY = loadPrivateKey();

    private TestJwt() {}

    static String forPlayer(UUID playerId) {
        var now = Instant.now();
        var header = encodeJson(Map.of("alg", "RS256", "typ", "JWT", "kid", "temporal-rift-e2e"));
        var claims = encodeJson(Map.of(
                "iss", "http://e2e-auth",
                "sub", playerId.toString(),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(3600).getEpochSecond()));
        var signingInput = header + "." + claims;
        return signingInput + "." + BASE64_URL.encodeToString(sign(signingInput));
    }

    private static String encodeJson(Object value) {
        return BASE64_URL.encodeToString(JSON.writeValueAsBytes(value));
    }

    private static byte[] sign(String signingInput) {
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(PRIVATE_KEY);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign the E2E bearer token", exception);
        }
    }

    private static PrivateKey loadPrivateKey() {
        try (var stream = TestJwt.class.getResourceAsStream("/oidc/private-key.pem")) {
            if (stream == null) {
                throw new IllegalStateException("Missing /oidc/private-key.pem");
            }
            var pem = new String(stream.readAllBytes(), StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            var keySpec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem));
            return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load the E2E signing key", exception);
        }
    }
}
