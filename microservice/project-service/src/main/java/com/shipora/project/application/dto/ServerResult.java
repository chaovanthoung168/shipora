package com.shipora.project.application.dto;

import com.shipora.project.domain.model.Server;
import java.time.Instant;
import java.util.UUID;

public record ServerResult(
        UUID id,
        UUID organizationId,
        String name,
        String host,
        int sshPort,
        String sshUsername,
        String sshHostKeyFingerprint,
        String status,
        Instant createdAt
) {
    public static ServerResult from(Server server) {
        return new ServerResult(
                server.getId(), server.getOrganizationId(), server.getName(),
                server.getHost(), server.getSshPort(), server.getSshUsername(),
                server.getSshHostKeyFingerprint(), server.getStatus().name(),
                server.getCreatedAt());
    }
}
