package com.shipora.git.application.port.out;

import java.util.List;

public interface GitProvider {
    void requireConfigured();
    String authorizationUrl(String state, String codeChallenge);
    VerifiedInstallation verifyInstallation(String externalId, String code, String codeVerifier);
    InstallationDiscovery discoverInstallations(String code, String codeVerifier);
    void requireActiveInstallation(String externalId);
    List<RepositorySnapshot> repositories(String externalId);
    default List<com.shipora.git.application.dto.CommitResult> commits(String installation, String repository,
            String fullName, String branch, int page, int size) {
        throw new com.shipora.git.domain.GitException(com.shipora.git.domain.GitException.Kind.NOT_CONFIGURED,
                "Repository commit access is not configured.");
    }
    default ReadAccess readAccess(String installation,String repository,String fullName,String branch){throw new com.shipora.git.domain.GitException(com.shipora.git.domain.GitException.Kind.NOT_CONFIGURED,"Repository source access is not configured.");}
    record ReadAccess(String token,java.time.Instant expiresAt,String branchHead){@Override public String toString(){return "ReadAccess[redacted]";}}

    record VerifiedInstallation(String externalId, String accountName) {}
    record InstallationDiscovery(List<VerifiedInstallation> accounts, String installationUrl) {
        public InstallationDiscovery { accounts = List.copyOf(accounts); }
    }
    record RepositorySnapshot(String externalId, String fullName, String cloneUrl,
                              String defaultBranch, boolean privateRepository) {}
}
