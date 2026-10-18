package com.shipora.git.application.dto;

import com.shipora.git.domain.GitRepository;
import java.time.Instant;
import java.util.UUID;

public record RepositoryResult(UUID id, UUID installationId, String externalId, String fullName,
                               String cloneUrl, String defaultBranch, boolean privateRepository,
                               boolean available, Instant createdAt) {
    public static RepositoryResult from(GitRepository value) {
        return new RepositoryResult(value.id(), value.installationId(), value.externalId(), value.fullName(),
                value.cloneUrl(), value.defaultBranch(), value.privateRepository(), value.available(), value.createdAt());
    }
}
