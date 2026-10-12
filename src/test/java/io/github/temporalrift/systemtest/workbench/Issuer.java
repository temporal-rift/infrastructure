package io.github.temporalrift.systemtest.workbench;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tokens from the deployment's OIDC issuer: client-credentials grants whose subject is the client id. */
final class Issuer {

    static final String CONTROL = "simulation:control";
    static final String DESIGNER = "simulation:read simulation:write simulation:observe";
    static final String ANALYST = "simulation:read simulation:write";
    static final String READER = "simulation:read";

    private final String issuer;

    Issuer(String issuer) {
        this.issuer = issuer;
    }

    /** A bearer token for {@code subject}; {@code scope} may be null for a participant-only identity. */
    String token(String subject, String scope) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("grant_type", "client_credentials");
        fields.put("client_id", subject);
        fields.put("client_secret", "not-checked");
        if (scope != null) {
            fields.put("scope", scope);
        }
        var response = Http.send(
                "POST",
                issuer + "/token",
                null,
                Http.form(fields),
                Map.of("Content-Type", "application/x-www-form-urlencoded"));
        response.requireStatus(200);
        return response.json().path("access_token").asString();
    }
}
