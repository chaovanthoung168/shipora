package com.shipora.deployment.application.service;
import com.shipora.deployment.application.port.out.*;
import com.shipora.deployment.domain.*;
import com.shipora.pipeline.*;
import lombok.RequiredArgsConstructor;
import java.util.*;
@RequiredArgsConstructor
public final class AutomaticPushService {
 private final PushInboxStore inbox;private final DeploymentStore deployments;private final AutomaticCatalog catalog;
 public void accept(UUID org,Protocol.Push push){
  DeploymentRules.id(push.eventId());DeploymentRules.id(push.repositoryId());DeploymentRules.commit(push.commitSha());
  if(push.branch()==null || !push.branch().matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,254}") || push.branch().contains("..") || push.branch().contains("//"))throw DeploymentRules.invalid("Invalid push branch.");
  inbox.accept(org,push);
 }
 public void dispatch(UUID org){
  var pending=inbox.claim(org);if(pending.isEmpty())return;var row=pending.get();
  try{
   if(row.targets()==null){inbox.targets(row,catalog.matching(org,row.push().repositoryId(),row.push().branch()));return;}
   UUID application=row.targets().get(row.cursor());ApplicationSnapshot snapshot;
   try{snapshot=catalog.configuration(org,application);}catch(InternalHttp.RemoteFailure e){if(e.status==404){inbox.advance(row);return;}throw e;}
   if(!snapshot.autoDeploy() || !row.push().repositoryId().equals(snapshot.repositoryId()) || !row.push().branch().equals(snapshot.branch())){inbox.advance(row);return;}
   var request=Deployment.builder().id(UUID.randomUUID()).organizationId(org).applicationId(application).configuration(snapshot).commitSha(row.push().commitSha())
    .branch(row.push().branch()).triggerType("GIT_PUSH").requestedBy("git-push").requestKey("push:"+row.push().eventId()+":"+application).status(DeploymentStatus.QUEUED).build();
   var saved=deployments.create(request);DeploymentService.sameRequest(saved,application,row.push().commitSha(),null);inbox.advance(row);
  }catch(RuntimeException e){inbox.postpone(row);}
 }
}
