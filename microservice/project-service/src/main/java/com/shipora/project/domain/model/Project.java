package com.shipora.project.domain.model;

import com.shipora.project.domain.exception.ProjectException;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Getter
public final class Project {

    private final UUID id;
    private final UUID organizationId;
    private final String name;
    private final String slug;
    private final String description;
    private final Instant createdAt;
    private final Instant updatedAt;

    @Builder
    private Project(
            UUID id,
            UUID organizationId,
            String name,
            String slug,
            String description,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "id is required");

        this.organizationId = Objects.requireNonNull(
                organizationId,
                "organizationId is required"
        );

        this.createdAt = Objects.requireNonNull(
                createdAt,
                "createdAt is required"
        );

        this.updatedAt = Objects.requireNonNull(
                updatedAt,
                "updatedAt is required"
        );

        this.name = normalizeName(name);
        this.slug = normalizeSlug(slug);

        if (description != null && description.length() > 5000) {
            throw new ProjectException.Invalid(
                    "Description must contain at most 5000 characters."
            );
        }

        this.description = description;
    }

    public static Project create(
            UUID organizationId,
            String name,
            String slug,
            String description,
            Instant now
    ) {
        return Project.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .name(name)
                .slug(slug)
                .description(description)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public Project update(
            String name,
            String slug,
            String description,
            Instant now
    ) {
        return Project.builder()
                .id(id)
                .organizationId(organizationId)
                .name(name)
                .slug(slug)
                .description(description)
                .createdAt(createdAt)
                .updatedAt(now)
                .build();
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new ProjectException.Invalid(
                    "Project name is required."
            );
        }

        String normalized = value.strip();

        if (normalized.length() > 150) {
            throw new ProjectException.Invalid(
                    "Project name must contain at most 150 characters."
            );
        }

        return normalized;
    }

    private static String normalizeSlug(String value) {
        if (value == null || value.isBlank()) {
            throw new ProjectException.Invalid(
                    "Project slug is required."
            );
        }

        String normalized = value.strip().toLowerCase(Locale.ROOT);

        if (normalized.length() > 100
                || !normalized.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new ProjectException.Invalid(
                    "Slug must contain at most 100 characters and use "
                            + "lowercase letters, numbers, and single hyphens."
            );
        }

        return normalized;
    }
}