package com.shipora.application.application.dto;

import com.shipora.application.domain.ManagedApplication;
import java.time.Instant;
import java.util.UUID;

public record ApplicationResult(UUID id, UUID organizationId, UUID projectId, UUID repositoryId, UUID serverId,
        String name, String slug, String branch, String dockerfilePath, String buildContext, int containerPort,
        String healthPath, boolean autoDeploy, long version, Instant createdAt, Instant updatedAt) {
    public static ApplicationResult from(ManagedApplication app) {
        return new ApplicationResult(app.getId(),app.getOrganizationId(),app.getProjectId(),app.getRepositoryId(),app.getServerId(),
                app.getName(),app.getSlug(),app.getBranch(),app.getDockerfilePath(),app.getBuildContext(),app.getContainerPort(),
                app.getHealthPath(),app.isAutoDeploy(),app.getVersion(),app.getCreatedAt(),app.getUpdatedAt());
    }
}
