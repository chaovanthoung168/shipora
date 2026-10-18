package com.shipora.git.configuration;

import com.shipora.git.application.port.out.GitHubSetupStore;
import com.shipora.git.application.port.in.GitHubSetupUseCases.Status;
import java.util.function.Supplier;

/** Stored instance credentials take precedence; the environment remains an optional migration path. */
public final class GitHubCredentialsSource implements Supplier<GitHubProperties> {
    private final GitHubSetupStore store;
    private final GitHubProperties fallback;
    private volatile GitHubProperties loaded;
    private volatile String slug;
    public GitHubCredentialsSource(GitHubSetupStore store, GitHubProperties fallback) { this.store = store; this.fallback = fallback; }
    @Override public GitHubProperties get() {
        if (loaded != null) return loaded;
        var saved = store.credentials();
        if (saved.isEmpty()) return fallback;
        var app = saved.orElseThrow();
        slug = app.slug();
        loaded = new GitHubProperties(app.appId(), app.clientId(), app.clientSecret(), null, app.callbackUrl(),
                app.webhookSecret(), fallback.apiVersion(), app.privateKeyPem());
        return loaded;
    }
    public Status status() {
        var settings = get();
        boolean ready = settings.appId() != null && settings.appId().matches("[1-9][0-9]*")
                && settings.clientId() != null && !settings.clientId().isBlank()
                && settings.clientSecret() != null && !settings.clientSecret().isBlank()
                && (settings.privateKey() != null || settings.privateKeyPem() != null) && settings.callbackUrl() != null;
        return new Status(ready, !ready, loaded != null ? "DATABASE" : ready ? "ENVIRONMENT" : "NONE",
                ready ? settings.appId() : null, loaded != null ? "https://github.com/apps/" + slug + "/installations/new" : null);
    }
}
