package com.shipora.application.application.port.out;

import java.util.UUID;

/** The adapter must use the authenticated caller's identity, never a caller-selected tenant header. */
public interface ResourceCatalog {
    void requireProject(UUID organizationId, UUID projectId);
    String requireRepository(UUID organizationId, UUID repositoryId);
    void requireActiveServer(UUID organizationId, UUID serverId);
}
