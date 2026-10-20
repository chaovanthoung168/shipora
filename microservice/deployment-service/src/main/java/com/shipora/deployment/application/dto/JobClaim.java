package com.shipora.deployment.application.dto;

import java.time.Instant;
import java.util.UUID;

public record JobClaim(UUID jobId,UUID leaseToken,Instant leaseExpiresAt,int attempt,DeploymentResult deployment,long fence) {
    public JobClaim(UUID jobId,UUID leaseToken,Instant leaseExpiresAt,int attempt,DeploymentResult deployment){this(jobId,leaseToken,leaseExpiresAt,attempt,deployment,0);}
    @Override public String toString(){return "JobClaim[jobId="+jobId+", leaseToken=redacted]";}
}
