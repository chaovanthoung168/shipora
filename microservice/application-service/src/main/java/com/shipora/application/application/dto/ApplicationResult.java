package com.shipora.application.application.dto;

import com.shipora.application.domain.ManagedApplication;
import com.shipora.application.domain.RuntimeType;
import java.time.Instant;
import java.util.UUID;

public record ApplicationResult(UUID id, UUID organizationId, UUID projectId, UUID repositoryId, UUID serverId,
        String name, String slug, String branch, String dockerfilePath, String buildContext, int containerPort,
        String healthPath, boolean autoDeploy, long version, Instant createdAt, Instant updatedAt,
        RuntimeType runtimeType, UUID kubernetesTargetId) {
    public ApplicationResult(UUID id,UUID organizationId,UUID projectId,UUID repositoryId,UUID serverId,String name,String slug,
            String branch,String dockerfilePath,String buildContext,int containerPort,String healthPath,boolean autoDeploy,long version,
            Instant createdAt,Instant updatedAt) {
        this(id,organizationId,projectId,repositoryId,serverId,name,slug,branch,dockerfilePath,buildContext,containerPort,healthPath,
                autoDeploy,version,createdAt,updatedAt,RuntimeType.DOCKER,null);
    }
    public static ApplicationResult from(ManagedApplication app) {
        return new ApplicationResult(app.getId(),app.getOrganizationId(),app.getProjectId(),app.getRepositoryId(),app.getServerId(),
                app.getName(),app.getSlug(),app.getBranch(),app.getDockerfilePath(),app.getBuildContext(),app.getContainerPort(),
                app.getHealthPath(),app.isAutoDeploy(),app.getVersion(),app.getCreatedAt(),app.getUpdatedAt(),app.getRuntimeType(),app.getKubernetesTargetId());
    }
}
