package com.shipora.application.adapter.in.web;

import com.shipora.application.adapter.in.security.CurrentOrganization;
import com.shipora.application.application.port.in.KubernetesTargetUseCases;
import com.shipora.application.application.port.in.KubernetesTargetUseCases.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/kubernetes-targets")
@RequiredArgsConstructor
public class KubernetesTargetController {
    private final KubernetesTargetUseCases service;
    private final CurrentOrganization organizations;
    public record CreateRequest(@NotBlank @Size(max=150) String name,@NotBlank @Size(max=2048) String apiServer,
            @NotBlank @Size(max=63) String namespace,@NotBlank @Size(max=16384) String token,@Size(max=131072) String certificateAuthorityData) {
        @Override public String toString() { return "CreateKubernetesTargetRequest[redacted]"; }
    }
    public record UpdateRequest(@NotNull @PositiveOrZero Long version,@Size(max=150) String name,
            @Size(max=16384) String token,@Size(max=131072) String certificateAuthorityData) {
        @Override public String toString() { return "UpdateKubernetesTargetRequest[redacted]"; }
    }
    public record CheckRequest(@NotNull @PositiveOrZero Long version) {}
    @PostMapping public ResponseEntity<Result> create(Authentication auth,@Valid @RequestBody CreateRequest in) {
        var result=service.create(organizations.require(auth),new Create(in.name(),in.apiServer(),in.namespace(),in.token(),in.certificateAuthorityData()));
        return ResponseEntity.created(URI.create("/api/kubernetes-targets/"+result.id())).cacheControl(CacheControl.noStore()).eTag("\""+result.version()+"\"").body(result);
    }
    @GetMapping public ResponseEntity<List<Result>> list(Authentication auth,@RequestParam(name="page",defaultValue="0") int page,
            @RequestParam(name="size",defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(organizations.require(auth),page,size));
    }
    @GetMapping("/{id}") public ResponseEntity<Result> get(Authentication auth,@PathVariable("id") UUID id) {
        return response(service.get(organizations.require(auth),id));
    }
    @PatchMapping("/{id}") public ResponseEntity<Result> update(Authentication auth,@PathVariable("id") UUID id,@Valid @RequestBody UpdateRequest in) {
        return response(service.update(organizations.require(auth),id,new Update(in.version(),in.name(),in.token(),in.certificateAuthorityData())));
    }
    @PostMapping("/{id}/connection-checks") public ResponseEntity<Result> check(Authentication auth,@PathVariable("id") UUID id,@Valid @RequestBody CheckRequest in) {
        return response(service.check(organizations.require(auth),id,in.version()));
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(Authentication auth,@PathVariable("id") UUID id,@RequestParam("version") long version) {
        service.delete(organizations.require(auth),id,version);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private ResponseEntity<Result> response(Result result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+result.version()+"\"").body(result);
    }
}
