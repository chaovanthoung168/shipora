package com.shipora.deployment.domain;

import java.util.UUID;

/** Immutable metadata only. No environment values, access tokens or secrets. */
public record ApplicationSnapshot(UUID id,UUID organizationId,UUID projectId,UUID repositoryId,
        String runtimeType,UUID serverId,UUID kubernetesTargetId,String name,String slug,String branch,
        String dockerfilePath,String buildContext,int containerPort,String healthPath,long version,boolean autoDeploy) {
    public ApplicationSnapshot(UUID id,UUID org,UUID project,UUID repo,String runtime,UUID server,UUID target,String name,String slug,String branch,String dockerfile,String context,int port,String health,long version){
        this(id,org,project,repo,runtime,server,target,name,slug,branch,dockerfile,context,port,health,version,false);
    }
}
