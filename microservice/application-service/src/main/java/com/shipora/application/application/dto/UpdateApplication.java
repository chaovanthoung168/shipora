package com.shipora.application.application.dto;

import java.util.UUID;
import com.shipora.application.domain.RuntimeType;

public record UpdateApplication(long version, UUID repositoryId, UUID serverId, String name, String slug,
        String branch, String dockerfilePath, String buildContext, Integer containerPort, String healthPath, Boolean autoDeploy,
        RuntimeType runtimeType, UUID kubernetesTargetId) {
    public UpdateApplication(long version,UUID repositoryId,UUID serverId,String name,String slug,String branch,String dockerfilePath,
            String buildContext,Integer containerPort,String healthPath,Boolean autoDeploy) {
        this(version,repositoryId,serverId,name,slug,branch,dockerfilePath,buildContext,containerPort,healthPath,autoDeploy,null,null);
    }
}
