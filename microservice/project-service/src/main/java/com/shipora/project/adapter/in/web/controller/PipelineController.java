package com.shipora.project.adapter.in.web.controller;
import com.shipora.project.adapter.in.security.CurrentOrganization;
import com.shipora.project.application.port.out.ServerRepository;
import com.shipora.project.domain.model.ServerStatus;
import com.shipora.pipeline.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequiredArgsConstructor
public class PipelineController {
 private final LeaseAuthorizer leases;private final CurrentOrganization organizations;private final ServerRepository servers;
 @PostMapping("/internal/worker/jobs/{id}/server-context")
 public ResponseEntity<Protocol.ServerContext> server(Authentication auth,@RequestHeader("Authorization") String authorization,@PathVariable("id") UUID id,@RequestBody Protocol.LeaseRequest in){
  UUID org=organizations.require(auth);var job=leases.authorize(org,id,in.leaseToken(),authorization);
  var config=job.deployment().configuration();
  if(!"DOCKER".equals(config.runtimeType()) || config.serverId()==null)throw new InternalHttp.RemoteFailure(409);
  var server=servers.find(org,config.serverId()).orElseThrow(()->new InternalHttp.RemoteFailure(404));
  if(server.getStatus()!=ServerStatus.ACTIVE)throw new InternalHttp.RemoteFailure(409);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Protocol.ServerContext(org,server.getId(),server.getHost(),server.getSshPort(),server.getSshUsername(),server.getCredentialSecretRef(),server.getSshHostKeyFingerprint()));
 }
}
