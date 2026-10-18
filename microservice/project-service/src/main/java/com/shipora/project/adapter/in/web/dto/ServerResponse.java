package com.shipora.project.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;

public record ServerResponse(
        UUID id,
        UUID organizationId,
        String name,
        String host,
        int sshPort,
        String sshUsername,
        String sshHostKeyFingerprint,
        String status,
        Instant createdAt
) {}
