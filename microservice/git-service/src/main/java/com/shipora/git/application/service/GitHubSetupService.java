package com.shipora.git.application.service;

import com.shipora.git.application.port.in.GitHubSetupUseCases;
import com.shipora.git.application.port.out.*;
import com.shipora.git.domain.GitException;
import lombok.RequiredArgsConstructor;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.shipora.git.domain.GitException.Kind.*;

@RequiredArgsConstructor
public final class GitHubSetupService implements GitHubSetupUseCases {
    private final GitHubSetupStore store;
    private final GitHubRegistration registration;
    private final SetupManifest manifest;
    private final Supplier<Status> statusSource;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Override public Status status() { return statusSource.get(); }

    @Override public Registration start(UUID org, String actor, String name, String githubOrganization) {
        if (status().configured()) throw new GitException(CONFLICT, "GitHub is already configured for this instance.");
        if (name != null && (!name.matches("[A-Za-z0-9 -]{1,34}") || name.isBlank()))
            throw new GitException(INVALID, "Use an App name of 1–34 letters, numbers, spaces or hyphens.");
        if (githubOrganization != null && !githubOrganization.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?"))
            throw new GitException(INVALID, "Invalid GitHub organization name.");
        store.prepareCredentialStorage();
        String state = "s_" + randomValue();
        var config = manifest.create(name == null ? "Shipora-" + randomValue().substring(0, 12) : name);
        Instant expires = clock.instant().plusSeconds(600);
        store.savePending(new GitHubSetupStore.Pending(GitService.hash(state), org, actor, config.authorizationCallback(), expires), clock.instant());
        String target = githubOrganization == null ? "https://github.com/settings/apps/new"
                : "https://github.com/organizations/" + githubOrganization + "/settings/apps/new";
        return new Registration(target + "?state=" + state, "POST", Map.of("manifest", config.json()), expires);
    }

    @Override public Completion complete(UUID org, String actor, String state, String code) {
        if (state == null || !state.matches("s_[A-Za-z0-9_-]{43}") || code == null || !code.matches("[A-Za-z0-9_-]{1,512}"))
            throw new GitException(INVALID, "Invalid GitHub setup callback. Start setup again.");
        if (status().configured()) throw new GitException(CONFLICT, "GitHub is already configured for this instance.");
        var pending = store.consumePending(GitService.hash(state), org, actor, clock.instant())
                .orElseThrow(() -> new GitException(INVALID, "GitHub setup expired, was already used, or belongs to another administrator."));
        var credentials = registration.convert(code, pending.callbackUrl());
        if (!store.saveCredentialsIfAbsent(credentials, clock.instant()))
            throw new GitException(CONFLICT, "Another administrator already configured GitHub.");
        return new Completion(true, credentials.appId(), credentials.installationUrl());
    }
    private String randomValue() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
