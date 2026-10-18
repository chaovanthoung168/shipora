package com.shipora.git.application.port.out;

import com.shipora.git.domain.*;
import java.time.Instant;
import java.util.*;

public interface GitStore {
    void saveState(ConnectionState state);
    Optional<ConnectionState> consumeState(String hash, Instant now);
    GitInstallation connect(UUID organizationId, GitProvider.VerifiedInstallation installation, Instant now);
    Optional<GitInstallation> installation(UUID organizationId, UUID id);
    List<GitInstallation> installations(UUID organizationId, int offset, int limit);
    List<GitRepository> repositories(UUID organizationId, UUID installationId, int offset, int limit);
    Optional<GitRepository> repository(UUID organizationId, UUID id);
    void replaceRepositories(GitInstallation installation, List<GitProvider.RepositorySnapshot> repositories, Instant now);
    boolean recordWebhook(WebhookDelivery delivery, Instant now);

    // The state is random, short-lived and consumed atomically; only its hash is stored.
    record ConnectionState(String hash, UUID organizationId, String installationExternalId,
                           String codeVerifier, Instant expiresAt) {
        @Override public String toString() { return "ConnectionState[redacted]"; }
    }
    record WebhookDelivery(String deliveryId, String eventType, String installationExternalId,
                           String repositoryExternalId, String commitSha, boolean invalidateInstallation,
                           boolean invalidateRepositories) {}
}
