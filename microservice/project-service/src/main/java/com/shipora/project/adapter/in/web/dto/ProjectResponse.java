package com.shipora.project.adapter.in.web.dto;


import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID organizationId,
        String name,
        String slug,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
}
