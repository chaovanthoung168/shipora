package com.shipora.project.application.dto;

import com.shipora.project.domain.model.Project;

import java.time.Instant;
import java.util.UUID;


public record ProjectResult(
        UUID id,
        UUID organizationId,
        String name,
        String slug,
        String description,
        Instant createdAt,
        Instant updatedAt
) {

    public static ProjectResult from(Project project) {
        return new ProjectResult(
                project.getId(),
                project.getOrganizationId(),
                project.getName(),
                project.getSlug(),
                project.getDescription(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
