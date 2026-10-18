package com.shipora.git.domain;

import java.time.Instant;
import java.util.UUID;

public record GitInstallation(UUID id, UUID organizationId, String externalId,
                              String accountName, boolean active, long revision, Instant createdAt) {}
