package com.shipora.application.adapter.in.web;

import com.shipora.application.adapter.in.security.CurrentOrganization;
import com.shipora.application.application.dto.*;
import com.shipora.application.application.port.in.ApplicationUseCases;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {
    private final ApplicationUseCases service;
    private final CurrentOrganization organizations;
    public record CreateRequest(@NotNull UUID projectId,@NotNull UUID repositoryId,@NotNull UUID serverId,
            @NotBlank @Size(max=150) String name,@NotBlank @Size(max=100) String slug,
            @Size(max=255) String branch,@Size(max=255) String dockerfilePath,@Size(max=255) String buildContext,
            @NotNull @Min(1) @Max(65535) Integer containerPort,@Size(max=255) String healthPath,Boolean autoDeploy) {
        CreateApplication input(){return new CreateApplication(projectId,repositoryId,serverId,name,slug,branch,dockerfilePath,buildContext,containerPort,healthPath,autoDeploy);}
    }
    public record UpdateRequest(@NotNull @PositiveOrZero Long version,UUID repositoryId,UUID serverId,@Size(max=150) String name,
            @Size(max=100) String slug,@Size(max=255) String branch,@Size(max=255) String dockerfilePath,@Size(max=255) String buildContext,
            @Min(1) @Max(65535) Integer containerPort,@Size(max=255) String healthPath,Boolean autoDeploy) {
        UpdateApplication input(){return new UpdateApplication(version,repositoryId,serverId,name,slug,branch,dockerfilePath,buildContext,containerPort,healthPath,autoDeploy);}
    }
    public record EnvironmentRequest(@NotNull @PositiveOrZero Long version,@NotNull Map<String,String> variables) {
        @Override public String toString(){return "EnvironmentRequest[redacted]";}
    }
    public record SecretRequest(@NotNull @PositiveOrZero Long version,@NotNull @Size(max=16384) String value) {
        @Override public String toString(){return "SecretRequest[redacted]";}
    }
    @PostMapping
    public ResponseEntity<ApplicationResult> create(Authentication auth,@Valid @RequestBody CreateRequest request) {
        var result=service.create(organizations.require(auth),request.input());
        return ResponseEntity.created(URI.create("/api/applications/"+result.id())).cacheControl(CacheControl.noStore()).eTag("\""+result.version()+"\"").body(result);
    }
    @GetMapping("/{id}")
    public ResponseEntity<ApplicationResult> get(Authentication auth,@PathVariable("id") UUID id){
        var result=service.get(organizations.require(auth),id);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+result.version()+"\"").body(result);
    }
    @GetMapping
    public ResponseEntity<List<ApplicationResult>> list(Authentication auth,
            @RequestParam(name="projectId",required=false) UUID projectId,@RequestParam(name="page",defaultValue="0") int page,@RequestParam(name="size",defaultValue="20") int size){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(organizations.require(auth),projectId,page,size));
    }
    @PatchMapping("/{id}")
    public ResponseEntity<ApplicationResult> update(Authentication auth,@PathVariable("id") UUID id,@Valid @RequestBody UpdateRequest request){
        var result=service.update(organizations.require(auth),id,request.input());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+result.version()+"\"").body(result);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth,@PathVariable("id") UUID id,@RequestParam("version") long version){
        service.delete(organizations.require(auth),id,version);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{id}/environment")
    public ResponseEntity<ApplicationUseCases.EnvironmentResult> environment(Authentication auth,@PathVariable("id") UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.environment(organizations.require(auth),id));
    }
    @PutMapping("/{id}/environment")
    public ResponseEntity<ApplicationUseCases.EnvironmentResult> environment(Authentication auth,@PathVariable("id") UUID id,@Valid @RequestBody EnvironmentRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.replaceEnvironment(organizations.require(auth),id,request.version(),request.variables()));
    }
    @GetMapping("/{id}/secrets")
    public ResponseEntity<ApplicationUseCases.SecretKeysResult> secrets(Authentication auth,@PathVariable("id") UUID id){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.secretKeys(organizations.require(auth),id));
    }
    @PutMapping("/{id}/secrets/{key}")
    public ResponseEntity<ApplicationUseCases.SecretKeysResult> secret(Authentication auth,@PathVariable("id") UUID id,@PathVariable("key") String key,@Valid @RequestBody SecretRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.putSecret(organizations.require(auth),id,request.version(),key,request.value()));
    }
    @DeleteMapping("/{id}/secrets/{key}")
    public ResponseEntity<ApplicationUseCases.SecretKeysResult> deleteSecret(Authentication auth,@PathVariable("id") UUID id,@PathVariable("key") String key,@RequestParam("version") long version){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.deleteSecret(organizations.require(auth),id,version,key));
    }
}
