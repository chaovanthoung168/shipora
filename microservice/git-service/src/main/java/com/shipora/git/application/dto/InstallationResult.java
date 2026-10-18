package com.shipora.git.application.dto;

import com.shipora.git.domain.GitInstallation;
import java.time.Instant;
import java.util.UUID;

public record InstallationResult(UUID id, String provider, String externalId, String accountName,
                                 boolean active, Instant createdAt) {
    public static InstallationResult from(GitInstallation value) {
        return new InstallationResult(value.id(), "GITHUB", value.externalId(), value.accountName(),
                value.active(), value.createdAt());
    }
}
