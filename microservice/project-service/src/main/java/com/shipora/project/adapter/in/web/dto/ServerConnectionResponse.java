package com.shipora.project.adapter.in.web.dto;

import java.time.Instant;
import java.util.UUID;

public record ServerConnectionResponse(
        UUID serverId,
        String status,
        String code,
        String message,
        Instant checkedAt
) {}
