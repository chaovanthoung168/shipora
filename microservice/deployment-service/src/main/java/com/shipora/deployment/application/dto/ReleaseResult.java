package com.shipora.deployment.application.dto;

import java.time.Instant;
import java.util.UUID;
public record ReleaseResult(UUID id,UUID applicationId,UUID deploymentId,String imageReference,String status,Instant activatedAt,Instant createdAt,String runtimeEndpoint) {}
