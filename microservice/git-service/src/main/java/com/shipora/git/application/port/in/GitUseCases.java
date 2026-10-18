package com.shipora.git.application.port.in;

import com.shipora.git.application.dto.*;
import java.util.List;
import java.util.UUID;

public interface GitUseCases {
    ConnectionResult startConnection(UUID organizationId, String installationExternalId);
    InstallationResult completeConnection(String state, String code);
    List<InstallationResult> installations(UUID organizationId, int page, int size);
    List<RepositoryResult> repositories(UUID organizationId, UUID installationId, int page, int size);
    RepositoryResult repository(UUID organizationId, UUID repositoryId);
    int synchronize(UUID organizationId, UUID installationId);
}
