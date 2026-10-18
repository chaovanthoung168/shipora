package com.shipora.git.application.port.out;

import com.shipora.git.application.port.in.GitConnectionUseCases.AccountChoice;
import java.time.Instant;
import java.util.*;

public interface ConnectionStore {
    void savePending(Pending pending);
    Optional<Pending> consumePending(String stateHash, UUID organizationId, Instant now);
    void saveChoices(UUID organizationId, String tokenHash, List<GitProvider.VerifiedInstallation> accounts, Instant expiresAt);
    List<AccountChoice> choices(UUID organizationId, String tokenHash, Instant now);
    Optional<GitProvider.VerifiedInstallation> consumeChoice(UUID organizationId, String tokenHash, UUID id, Instant now);

    record Pending(String stateHash, UUID organizationId, String tokenHash, String verifier, Instant expiresAt) {
        @Override public String toString() { return "Pending[redacted]"; }
    }
}
