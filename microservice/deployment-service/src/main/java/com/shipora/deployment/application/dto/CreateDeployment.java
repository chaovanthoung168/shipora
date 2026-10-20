package com.shipora.deployment.application.dto;

import java.util.UUID;
public record CreateDeployment(UUID applicationId,String commitSha) {}
