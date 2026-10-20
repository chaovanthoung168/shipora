package com.shipora.deployment.application.dto;

import java.time.Instant;
import java.util.UUID;
public record DeploymentEvent(long id,UUID deploymentId,String level,String eventType,String message,Instant createdAt) {}
