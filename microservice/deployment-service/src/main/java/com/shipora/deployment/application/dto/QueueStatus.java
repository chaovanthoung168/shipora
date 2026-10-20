package com.shipora.deployment.application.dto;

import java.time.Instant;

public record QueueStatus(String reason,Instant lastWorkerSeenAt,int runningDeployments,Instant queuedAt) {}
