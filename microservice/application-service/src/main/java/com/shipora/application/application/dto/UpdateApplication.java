package com.shipora.application.application.dto;

import java.util.UUID;

public record UpdateApplication(long version, UUID repositoryId, UUID serverId, String name, String slug,
        String branch, String dockerfilePath, String buildContext, Integer containerPort, String healthPath, Boolean autoDeploy) {}
