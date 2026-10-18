package com.shipora.git.domain;

import java.time.Instant;
import java.util.UUID;

public record GitRepository(UUID id, UUID installationId, String externalId, String fullName,
                            String cloneUrl, String defaultBranch, boolean privateRepository,
                            boolean available, Instant createdAt) {}
