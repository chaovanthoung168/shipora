package com.shipora.project.application.dto;

import java.time.Instant;
import java.util.UUID;

public record ServerConnectionResult(
        UUID serverId,
        String status,
        String code,
        String message,
        Instant checkedAt
) {}
