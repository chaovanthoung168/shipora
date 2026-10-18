package com.shipora.project.adapter.out.persistence;

import com.shipora.project.domain.model.Project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects", schema = "project")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProjectEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 100)
    private String slug;

    @Column(columnDefinition = "text")
    private String description;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    static ProjectEntity fromDomain(Project project) {
        return ProjectEntity.builder()
                .id(project.getId())
                .organizationId(project.getOrganizationId())
                .name(project.getName())
                .slug(project.getSlug())
                .description(project.getDescription())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    void apply(Project project) {
        this.name = project.getName();
        this.slug = project.getSlug();
        this.description = project.getDescription();
        this.updatedAt = project.getUpdatedAt();
    }

    Project toDomain() {
        return Project.builder()
                .id(id)
                .organizationId(organizationId)
                .name(name)
                .slug(slug)
                .description(description)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .build();
    }
}
