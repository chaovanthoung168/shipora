package com.shipora.git.application.service;
import com.shipora.git.application.port.out.*;
import com.shipora.pipeline.*;
import lombok.RequiredArgsConstructor;
import java.net.URI;
@RequiredArgsConstructor
public final class WorkerSourceService {
 private final GitStore store;private final GitProvider provider;
 public Protocol.SourceContext source(Protocol.Job job){
  var d=job.deployment();var repo=store.repository(d.organizationId(),d.configuration().repositoryId()).orElseThrow(()->new InternalHttp.RemoteFailure(404));
  if(!repo.available())throw new InternalHttp.RemoteFailure(409);
  var installation=store.installation(d.organizationId(),repo.installationId()).orElseThrow(()->new InternalHttp.RemoteFailure(404));
  if(!installation.active())throw new InternalHttp.RemoteFailure(409);
  try{var uri=URI.create(repo.cloneUrl());if(!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getPort()!=-1 || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !uri.getPath().equals("/"+repo.fullName()+".git"))throw new IllegalArgumentException();}
  catch(RuntimeException e){throw new InternalHttp.RemoteFailure(503);}
  var access=provider.readAccess(installation.externalId(),repo.externalId(),repo.fullName(),"GIT_PUSH".equals(d.triggerType())?d.branch():null);
  return new Protocol.SourceContext(repo.cloneUrl(),access.token(),access.expiresAt(),access.branchHead());
 }
}
