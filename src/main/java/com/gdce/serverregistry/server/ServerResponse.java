package com.gdce.serverregistry.server;

import java.time.Instant;

public record ServerResponse(
        Long id,
        String hostname,
        String ipAddress,
        String serverType,
        String systemName,
        int port,
        Instant createdAt
) {

    public static ServerResponse from(Server server) {
        return new ServerResponse(
                server.getId(),
                server.getHostname(),
                server.getIpAddress(),
                server.getServerType(),
                server.getSystemName(),
                server.getPort(),
                server.getCreatedAt().toInstant());
    }
}
