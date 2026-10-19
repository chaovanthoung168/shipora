package com.shipora.application.domain;

import lombok.Builder;
import lombok.Getter;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder(toBuilder = true)
public final class ManagedApplication {
    private final UUID id, organizationId, projectId, repositoryId, serverId;
    private final String name, slug, branch, dockerfilePath, buildContext, healthPath;
    private final int containerPort;
    private final boolean autoDeploy;
    private final long version;
    private final Instant createdAt, updatedAt;
}
