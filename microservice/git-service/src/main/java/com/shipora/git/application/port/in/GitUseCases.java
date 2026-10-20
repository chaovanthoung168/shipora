package com.shipora.git.application.port.in;

import com.shipora.git.application.dto.*;
import java.util.List;
import java.util.UUID;

public interface GitUseCases {
    ConnectionResult startConnection(UUID organizationId, String installationExternalId);
    InstallationResult completeConnection(String state, String code);
    List<InstallationResult> installations(UUID organizationId, int page, int size);
    List<RepositoryResult> repositories(UUID organizationId, UUID installationId, int page, int size);
    List<RepositoryResult> searchRepositories(UUID organizationId, UUID installationId, String query, int page, int size);
    RepositoryResult repository(UUID organizationId, UUID repositoryId);
    List<CommitResult> commits(UUID organizationId, UUID repositoryId, String branch, int page, int size);
    int synchronize(UUID organizationId, UUID installationId);
}
