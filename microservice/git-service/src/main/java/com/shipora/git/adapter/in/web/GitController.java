package com.shipora.git.adapter.in.web;

import com.shipora.git.adapter.in.security.CurrentOrganization;
import com.shipora.git.application.dto.*;
import com.shipora.git.application.port.in.GitUseCases;
import com.shipora.git.application.port.in.GitConnectionUseCases;
import com.shipora.git.domain.GitException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/git")
@RequiredArgsConstructor
public class GitController {
    private final GitUseCases useCases;
    private final CurrentOrganization organization;
    private final SignedWebhookHandler webhook;
    private final GitConnectionUseCases connectionUseCases;

    public record ConnectRequest(@Pattern(regexp = "[1-9][0-9]{0,19}") String installationId) {}
    public record AuthorizeRequest(@NotBlank @Size(max = 45) String state, @NotBlank @Size(max = 512) String code) {}
    public record SelectAccountRequest(@NotNull UUID choiceId) {}
    public record SyncResponse(int synchronizedRepositories) {}
    public record WebhookResponse(boolean received, boolean duplicate) {}

    @PostMapping("/connections")
    public ResponseEntity<?> connect(Authentication auth, @Valid @RequestBody(required = false) ConnectRequest request) {
        UUID org = organization.require(auth);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(request == null || request.installationId() == null ? connectionUseCases.start(org)
                        : useCases.startConnection(org, request.installationId()));
    }
    @GetMapping("/github/callback")
    public ResponseEntity<?> callback(Authentication auth, @RequestParam("state") String state,
                                      @RequestParam(name = "code", required = false) String code) {
        if (state.length() == 45 && state.startsWith("c_")) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(connectionUseCases.complete(organization.require(auth), state, code));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(useCases.completeConnection(state, code));
    }
    @PostMapping("/github/callback")
    public ResponseEntity<?> callbackFromApi(Authentication auth, @Valid @RequestBody AuthorizeRequest request) {
        return callback(auth, request.state(), request.code());
    }
    @GetMapping("/connections/accounts")
    public ResponseEntity<List<GitConnectionUseCases.AccountChoice>> accounts(Authentication auth,
            @RequestHeader("X-Git-Connection-Token") String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(connectionUseCases.choices(organization.require(auth), token));
    }
    @PostMapping("/connections/accounts")
    public ResponseEntity<InstallationResult> selectAccount(Authentication auth,
            @RequestHeader("X-Git-Connection-Token") String token, @Valid @RequestBody SelectAccountRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(connectionUseCases.select(organization.require(auth), token, request.choiceId()));
    }
    @GetMapping("/installations")
    public List<InstallationResult> installations(Authentication auth, @RequestParam(name = "page", defaultValue = "0") int page,
                                                  @RequestParam(name = "size", defaultValue = "20") int size) {
        return useCases.installations(organization.require(auth), page, size);
    }
    @GetMapping("/installations/{id}/repositories")
    public List<RepositoryResult> repositories(Authentication auth, @PathVariable("id") UUID id,
                                               @RequestParam(name = "page", defaultValue = "0") int page,
                                               @RequestParam(name = "size", defaultValue = "20") int size,
                                               @RequestParam(name = "q", required = false) String query) {
        UUID org = organization.require(auth);
        return query == null ? useCases.repositories(org, id, page, size)
                : useCases.searchRepositories(org, id, query, page, size);
    }
    @GetMapping("/repositories/{id}")
    public RepositoryResult repository(Authentication auth, @PathVariable("id") UUID id) {
        return useCases.repository(organization.require(auth), id);
    }
    @PostMapping("/installations/{id}/sync")
    public SyncResponse synchronize(Authentication auth, @PathVariable("id") UUID id) {
        return new SyncResponse(useCases.synchronize(organization.require(auth), id));
    }
    @GetMapping("/repositories/{id}/commits")
    public ResponseEntity<List<CommitResult>> commits(Authentication auth, @PathVariable("id") UUID id,
            @RequestParam("branch") String branch,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(useCases.commits(organization.require(auth), id, branch, page, size));
    }
    @PostMapping("/webhooks/github")
    public WebhookResponse webhook(HttpServletRequest request,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event) throws IOException {
        byte[] body = request.getInputStream().readNBytes(SignedWebhookHandler.MAX_BODY + 1);
        if (body.length > SignedWebhookHandler.MAX_BODY) throw new GitException(GitException.Kind.TOO_LARGE, "Webhook body exceeds 2 MiB.");
        return new WebhookResponse(true, !webhook.handle(signature, deliveryId, event, body));
    }
}
