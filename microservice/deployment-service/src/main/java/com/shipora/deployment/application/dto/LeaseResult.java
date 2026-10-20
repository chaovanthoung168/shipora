package com.shipora.deployment.application.dto;

import com.shipora.deployment.domain.DeploymentStatus;
import java.time.Instant;
import java.util.UUID;
public record LeaseResult(UUID jobId,Instant leaseExpiresAt,DeploymentStatus status,long version) {}
