package com.shipora.git.application.service;

import com.shipora.git.application.dto.*;
import com.shipora.git.application.port.in.GitUseCases;
import com.shipora.git.application.port.out.*;
import com.shipora.git.domain.*;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

import static com.shipora.git.domain.GitException.Kind.*;

@RequiredArgsConstructor
public final class GitService implements GitUseCases {
    private final GitStore store;
    private final GitProvider provider;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Override public ConnectionResult startConnection(UUID organizationId, String externalId) {
        if (externalId == null || !externalId.matches("[1-9][0-9]{0,19}")) {
            throw new GitException(INVALID, "A positive GitHub installation ID is required.");
        }
        provider.requireConfigured();
        String state = randomValue();
        String verifier = randomValue();
        Instant expires = clock.instant().plus(Duration.ofMinutes(10));
        store.saveState(new GitStore.ConnectionState(hash(state), organizationId, externalId, verifier, expires));
        return new ConnectionResult(provider.authorizationUrl(state, challenge(verifier)), expires);
    }

    @Override public InstallationResult completeConnection(String state, String code) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}") || code == null || code.isBlank() || code.length() > 512) {
            throw new GitException(INVALID, "Invalid GitHub authorization response.");
        }
        var pending = store.consumeState(hash(state), clock.instant())
                .orElseThrow(() -> new GitException(INVALID, "Connection expired or already used. Start a new connection."));
        var verified = provider.verifyInstallation(pending.installationExternalId(), code, pending.codeVerifier());
        if (!pending.installationExternalId().equals(verified.externalId())) {
            throw new GitException(FORBIDDEN, "Installation verification failed.");
        }
        return InstallationResult.from(store.connect(pending.organizationId(), verified, clock.instant()));
    }

    @Override public List<InstallationResult> installations(UUID org, int page, int size) {
        return store.installations(org, offset(page, size), size).stream().map(InstallationResult::from).toList();
    }
    @Override public List<RepositoryResult> repositories(UUID org, UUID id, int page, int size) {
        int offset = offset(page, size);
        requireInstallation(org, id);
        return store.repositories(org, id, offset, size).stream().map(RepositoryResult::from).toList();
    }
    @Override public RepositoryResult repository(UUID org, UUID id) {
        return RepositoryResult.from(store.repository(org, id)
                .orElseThrow(() -> new GitException(NOT_FOUND, "Repository not found.")));
    }
    @Override public int synchronize(UUID org, UUID id) {
        var installation = requireInstallation(org, id);
        if (!installation.active()) throw new GitException(CONFLICT, "Reconnect this installation before synchronizing.");
        // No database transaction is held during GitHub network requests.
        var snapshot = provider.repositories(installation.externalId());
        store.replaceRepositories(installation, snapshot, clock.instant());
        return snapshot.size();
    }
    private GitInstallation requireInstallation(UUID org, UUID id) {
        return store.installation(org, id).orElseThrow(() -> new GitException(NOT_FOUND, "Installation not found."));
    }
    private int offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new GitException(INVALID, "Use page >= 0 and size between 1 and 100.");
        }
        return page * size;
    }
    private String randomValue() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) { return HexFormat.of().formatHex(digest(value)); }
    private static String challenge(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(value)); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
