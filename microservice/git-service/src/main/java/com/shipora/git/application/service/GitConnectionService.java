package com.shipora.git.application.service;

import com.shipora.git.application.dto.*;
import com.shipora.git.application.port.in.GitConnectionUseCases;
import com.shipora.git.application.port.out.*;
import com.shipora.git.domain.GitException;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static com.shipora.git.domain.GitException.Kind.INVALID;

@RequiredArgsConstructor
public final class GitConnectionService implements GitConnectionUseCases {
    private final ConnectionStore connectionStore;
    private final GitStore gitStore;
    private final GitProvider provider;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Override public DiscoveryConnectionResult start(UUID org) {
        provider.requireConfigured();
        String state = "c_" + randomValue();
        String verifier = randomValue();
        String token = randomValue();
        Instant expires = clock.instant().plusSeconds(600);
        connectionStore.savePending(new ConnectionStore.Pending(GitService.hash(state), org, GitService.hash(token), verifier, expires));
        return new DiscoveryConnectionResult(provider.authorizationUrl(state, challenge(verifier)), expires, token);
    }
    @Override public ConnectionAuthorizationResult complete(UUID org, String state, String code) {
        if (state == null || !state.matches("c_[A-Za-z0-9_-]{43}") || code == null || code.isBlank() || code.length() > 512) {
            throw new GitException(INVALID, "GitHub authorization was cancelled or is invalid. Connect again.");
        }
        var pending = connectionStore.consumePending(GitService.hash(state), org, clock.instant())
                .orElseThrow(() -> new GitException(INVALID, "This connection expired or belongs to another organization. Start a new connection."));
        var discovery = provider.discoverInstallations(code, pending.verifier());
        Instant expires = clock.instant().plusSeconds(600);
        connectionStore.saveChoices(pending.organizationId(), pending.tokenHash(), discovery.accounts(), expires);
        return new ConnectionAuthorizationResult(true, discovery.accounts().size(), discovery.installationUrl(), expires);
    }
    @Override public List<AccountChoice> choices(UUID org, String token) {
        return connectionStore.choices(org, binding(token), clock.instant());
    }
    @Override public InstallationResult select(UUID org, String token, UUID choiceId) {
        var choice = connectionStore.consumeChoice(org, binding(token), choiceId, clock.instant())
                .orElseThrow(() -> new GitException(INVALID, "This account selection expired. Connect again."));
        // Recheck App access immediately before linking; user ownership was verified during OAuth.
        provider.requireActiveInstallation(choice.externalId());
        return InstallationResult.from(gitStore.connect(org, choice, clock.instant()));
    }
    private String binding(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new GitException(INVALID, "A valid X-Git-Connection-Token header is required.");
        }
        return GitService.hash(token);
    }
    private String randomValue() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
