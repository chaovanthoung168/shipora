package com.shipora.deployment.adapter.in.web;

import com.shipora.deployment.adapter.in.security.CurrentOrganization;
import com.shipora.deployment.application.dto.*;
import com.shipora.deployment.application.port.in.DeploymentUseCases;
import com.shipora.deployment.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/internal/deployment-jobs")
@RequiredArgsConstructor
public class WorkerController {
    private final DeploymentUseCases service;
    private final CurrentOrganization organizations;
    public record HeartbeatRequest(@NotNull UUID leaseToken){@Override public String toString(){return "HeartbeatRequest[redacted]";}}
    public record EventsRequest(@NotNull UUID leaseToken,@NotNull @Size(min=1,max=50) List<WorkerEvent> events){@Override public String toString(){return "EventsRequest[redacted]";}}
    @PostMapping("/{id}/events") public ResponseEntity<Void> events(Authentication auth,@PathVariable("id") UUID id,@Valid @RequestBody EventsRequest in){
        service.appendEvents(organizations.require(auth),auth.getName(),id,in.leaseToken(),in.events());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    public record StatusRequest(@NotNull UUID leaseToken,@NotNull DeploymentStatus expectedStatus,@NotNull DeploymentStatus status,
            @Size(max=1024) String imageReference,FailureCode failureCode,@Size(max=2048) String runtimeEndpoint){@Override public String toString(){return "StatusRequest[redacted]";}}
    @PostMapping("/{id}/authorizations") public ResponseEntity<JobClaim> authorize(Authentication auth,@PathVariable("id") UUID id,@Valid @RequestBody HeartbeatRequest in){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.authorize(organizations.require(auth),auth.getName(),id,in.leaseToken()));
    }
    @PostMapping("/claims") public ResponseEntity<JobClaim> claim(Authentication auth){
        var claim=service.claim(organizations.require(auth),auth.getName());
        return claim.map(value->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(()->ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }
    @PostMapping("/{id}/heartbeats") public ResponseEntity<LeaseResult> heartbeat(Authentication auth,@PathVariable("id") UUID id,@Valid @RequestBody HeartbeatRequest in){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.heartbeat(organizations.require(auth),auth.getName(),id,in.leaseToken()));
    }
    @PutMapping("/{id}/status") public ResponseEntity<DeploymentResult> progress(Authentication auth,@PathVariable("id") UUID id,@Valid @RequestBody StatusRequest in){
        var result=service.progress(organizations.require(auth),auth.getName(),id,new ChangeDeploymentStatus(in.leaseToken(),in.expectedStatus(),in.status(),in.imageReference(),in.failureCode(),in.runtimeEndpoint()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+result.version()+"\"").body(result);
    }
}
