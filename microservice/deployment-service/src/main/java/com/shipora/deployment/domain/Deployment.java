package com.shipora.deployment.domain;

import lombok.Builder;
import lombok.Getter;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder(toBuilder=true)
public final class Deployment {
    private final UUID id,organizationId,applicationId,retryOf;
    private final String commitSha,branch,triggerType,requestedBy,requestKey,imageReference,runtimeEndpoint;
    private final ApplicationSnapshot configuration;
    private final DeploymentStatus status;
    private final FailureCode failureCode;
    private final long version;
    private final Instant createdAt,startedAt,finishedAt;
}
