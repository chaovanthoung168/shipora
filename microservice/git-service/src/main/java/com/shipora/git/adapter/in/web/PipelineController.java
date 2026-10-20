package com.shipora.git.adapter.in.web;
import com.shipora.git.adapter.in.security.CurrentOrganization;
import com.shipora.git.application.service.WorkerSourceService;
import com.shipora.pipeline.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequiredArgsConstructor
public class PipelineController {
 private final LeaseAuthorizer leases;private final CurrentOrganization organizations;private final WorkerSourceService sources;
 @PostMapping("/internal/worker/jobs/{id}/source-context")
 public ResponseEntity<Protocol.SourceContext> source(Authentication auth,@RequestHeader("Authorization") String authorization,@PathVariable("id") UUID id,@RequestBody Protocol.LeaseRequest in){
  var job=leases.authorize(organizations.require(auth),id,in.leaseToken(),authorization);
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sources.source(job));
 }
}
