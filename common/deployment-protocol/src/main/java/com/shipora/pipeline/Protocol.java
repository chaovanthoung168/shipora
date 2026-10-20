package com.shipora.pipeline;
import java.time.Instant;
import java.util.*;
public final class Protocol {
 private Protocol(){}
 public record Configuration(UUID id,UUID organizationId,UUID projectId,UUID repositoryId,String runtimeType,UUID serverId,UUID kubernetesTargetId,
  String name,String slug,String branch,String dockerfilePath,String buildContext,int containerPort,String healthPath,long version,boolean autoDeploy){}
 public record DeploymentInfo(UUID id,UUID organizationId,UUID applicationId,String commitSha,String branch,String triggerType,String status,Configuration configuration){}
 public record Job(UUID jobId,UUID leaseToken,Instant leaseExpiresAt,int attempt,DeploymentInfo deployment,long fence){
  @Override public String toString(){return "Job[redacted]";}
 }
 public record LeaseRequest(UUID leaseToken){@Override public String toString(){return "LeaseRequest[redacted]";}}
 public record KubernetesConnection(String apiServer,String namespace,String token,String certificateAuthorityData){
  @Override public String toString(){return "KubernetesConnection[redacted]";}
 }
 public record ApplicationContext(long version,Map<String,String> environment,KubernetesConnection kubernetes){
  public ApplicationContext{environment=Map.copyOf(environment);}
  @Override public String toString(){return "ApplicationContext[redacted]";}
 }
 public record SourceContext(String cloneUrl,String token,Instant expiresAt,String branchHead){
  @Override public String toString(){return "SourceContext[redacted]";}
 }
 public record ServerContext(UUID organizationId,UUID id,String host,int port,String username,String credentialRef,String fingerprint){}
 public record Push(UUID eventId,UUID repositoryId,String commitSha,String branch){}
}
