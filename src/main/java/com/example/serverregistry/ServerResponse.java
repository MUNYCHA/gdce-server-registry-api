package com.example.serverregistry;

import java.time.Instant;

public record ServerResponse(
        Long id,
        String hostname,
        String ipAddress,
        String serverType,
        int port,
        Instant createdAt
) {

    public static ServerResponse from(Server server) {
        return new ServerResponse(
                server.getId(),
                server.getHostname(),
                server.getIpAddress(),
                server.getServerType(),
                server.getPort(),
                server.getCreatedAt().toInstant());
    }
}
