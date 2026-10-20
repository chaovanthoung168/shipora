package com.shipora.deployment.application.dto;

import com.shipora.deployment.domain.*;
import java.time.Instant;
import java.util.UUID;

public record DeploymentResult(UUID id,UUID organizationId,UUID applicationId,String commitSha,String branch,
        String triggerType,String requestedBy,UUID retryOf,DeploymentStatus status,FailureCode failureCode,String imageReference,
        long version,ApplicationSnapshot configuration,Instant createdAt,Instant startedAt,Instant finishedAt,String runtimeEndpoint) {
    public static DeploymentResult from(Deployment d){return new DeploymentResult(d.getId(),d.getOrganizationId(),d.getApplicationId(),d.getCommitSha(),d.getBranch(),
            d.getTriggerType(),d.getRequestedBy(),d.getRetryOf(),d.getStatus(),d.getFailureCode(),d.getImageReference(),d.getVersion(),d.getConfiguration(),d.getCreatedAt(),d.getStartedAt(),d.getFinishedAt(),d.getRuntimeEndpoint());}
}
