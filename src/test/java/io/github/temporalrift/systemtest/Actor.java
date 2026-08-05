package io.github.temporalrift.systemtest;

import java.util.UUID;

record Actor(UUID playerId, String name, String token) {

    static Actor named(String name) {
        var playerId = UUID.randomUUID();
        return new Actor(playerId, name, TestJwt.forPlayer(playerId));
    }
}
