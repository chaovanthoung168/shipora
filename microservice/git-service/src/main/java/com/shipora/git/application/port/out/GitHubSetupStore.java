package com.shipora.git.application.port.out;

import com.shipora.git.application.dto.GitHubAppCredentials;
import java.net.URI;
import java.time.Instant;
import java.util.*;

public interface GitHubSetupStore {
    void prepareCredentialStorage();
    Optional<GitHubAppCredentials> credentials();
    boolean saveCredentialsIfAbsent(GitHubAppCredentials credentials, Instant now);
    void savePending(Pending pending, Instant now);
    Optional<Pending> consumePending(String stateHash, UUID organizationId, String actor, Instant now);
    record Pending(String stateHash, UUID organizationId, String actor, URI callbackUrl, Instant expiresAt) {
        @Override public String toString() { return "SetupPending[redacted]"; }
    }
}
