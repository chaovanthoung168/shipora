package com.shipora.git.adapter.in.web;

import com.shipora.git.adapter.in.security.CurrentOrganization;
import com.shipora.git.application.port.in.GitHubSetupUseCases;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/git/setup/github")
@RequiredArgsConstructor
public class GitHubSetupController {
    private final GitHubSetupUseCases setup;
    private final CurrentOrganization organization;
    public record SetupRequest(@Size(max = 34) String name, @Size(max = 39) String githubOrganization) {}
    public record CallbackRequest(@NotBlank @Size(max = 45) String state, @NotBlank @Size(max = 512) String code) {
        @Override public String toString() { return "GitHubSetupCallback[redacted]"; }
    }
    @GetMapping
    public ResponseEntity<GitHubSetupUseCases.Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(setup.status());
    }
    @PostMapping
    public ResponseEntity<GitHubSetupUseCases.Registration> start(Authentication auth,
            @Valid @RequestBody(required = false) SetupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(setup.start(organization.require(auth), auth.getName(),
                        request == null ? null : request.name(), request == null ? null : request.githubOrganization()));
    }
    @GetMapping("/callback")
    public ResponseEntity<GitHubSetupUseCases.Completion> callback(Authentication auth,
            @RequestParam String state, @RequestParam(required = false) String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(setup.complete(organization.require(auth), auth.getName(), state, code));
    }
    @PostMapping("/callback")
    public ResponseEntity<GitHubSetupUseCases.Completion> callbackFromApi(Authentication auth,
            @Valid @RequestBody CallbackRequest request) {
        return callback(auth, request.state(), request.code());
    }
}
