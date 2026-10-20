package com.shipora.deployment.adapter.in.web;

import com.shipora.deployment.adapter.in.security.CurrentOrganization;
import com.shipora.deployment.application.dto.*;
import com.shipora.deployment.application.port.in.DeploymentUseCases;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class DeploymentController {
    private final DeploymentUseCases service;
    private final CurrentOrganization organizations;
    public record CreateRequest(@NotNull UUID applicationId,@NotBlank @Size(max=64) String commitSha){}
    public record CancelRequest(@NotNull @PositiveOrZero Long version){}
    @PostMapping("/api/deployments") public ResponseEntity<DeploymentResult> create(Authentication auth,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateRequest in){
        return accepted(service.create(organizations.require(auth),auth.getName(),key,new CreateDeployment(in.applicationId(),in.commitSha())));
    }
    @PostMapping("/api/deployments/{id}/retries") public ResponseEntity<DeploymentResult> retry(Authentication auth,@PathVariable("id") UUID id,@RequestHeader("Idempotency-Key") String key){
        return accepted(service.retry(organizations.require(auth),auth.getName(),id,key));
    }
    @GetMapping("/api/deployments/{id}") public ResponseEntity<DeploymentResult> get(Authentication auth,@PathVariable("id") UUID id){return response(service.get(organizations.require(auth),id));}
    @GetMapping("/api/deployments/{id}/queue-status") public ResponseEntity<QueueStatus> queueStatus(Authentication auth,@PathVariable("id") UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.queueStatus(organizations.require(auth),id));
    }
    @GetMapping("/api/deployments") public ResponseEntity<List<DeploymentResult>> list(Authentication auth,@RequestParam(name="applicationId",required=false) UUID app,
            @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(organizations.require(auth),app,page,size));
    }
    @PostMapping("/api/deployments/{id}/cancellations") public ResponseEntity<DeploymentResult> cancel(Authentication auth,@PathVariable("id") UUID id,@Valid @RequestBody CancelRequest in){
        return response(service.cancel(organizations.require(auth),id,in.version()));
    }
    @GetMapping("/api/deployments/{id}/events") public ResponseEntity<List<DeploymentEvent>> events(Authentication auth,@PathVariable("id") UUID id,
            @RequestParam(name="afterId",defaultValue="0") long after,@RequestParam(name="limit",defaultValue="100") int limit){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.events(organizations.require(auth),id,after,limit));
    }
    @GetMapping("/api/applications/{id}/releases") public ResponseEntity<List<ReleaseResult>> releases(Authentication auth,@PathVariable("id") UUID id,
            @RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.releases(organizations.require(auth),id,page,size));
    }
    private ResponseEntity<DeploymentResult> accepted(DeploymentResult result){return ResponseEntity.accepted().location(URI.create("/api/deployments/"+result.id()))
            .cacheControl(CacheControl.noStore()).eTag("\""+result.version()+"\"").body(result);}
    private ResponseEntity<DeploymentResult> response(DeploymentResult result){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+result.version()+"\"").body(result);}
}
