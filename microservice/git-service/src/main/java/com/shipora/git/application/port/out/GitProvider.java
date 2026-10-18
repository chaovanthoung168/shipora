package com.shipora.git.application.port.out;

import java.util.List;

public interface GitProvider {
    void requireConfigured();
    String authorizationUrl(String state, String codeChallenge);
    VerifiedInstallation verifyInstallation(String externalId, String code, String codeVerifier);
    InstallationDiscovery discoverInstallations(String code, String codeVerifier);
    void requireActiveInstallation(String externalId);
    List<RepositorySnapshot> repositories(String externalId);

    record VerifiedInstallation(String externalId, String accountName) {}
    record InstallationDiscovery(List<VerifiedInstallation> accounts, String installationUrl) {
        public InstallationDiscovery { accounts = List.copyOf(accounts); }
    }
    record RepositorySnapshot(String externalId, String fullName, String cloneUrl,
                              String defaultBranch, boolean privateRepository) {}
}
