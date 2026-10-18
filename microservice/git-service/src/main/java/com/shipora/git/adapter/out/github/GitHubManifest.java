package com.shipora.git.adapter.out.github;

import com.shipora.git.application.port.out.SetupManifest;
import com.shipora.git.configuration.GitSetupProperties;
import com.shipora.git.domain.GitException;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.util.*;
import static com.shipora.git.domain.GitException.Kind.NOT_CONFIGURED;

public final class GitHubManifest implements SetupManifest {
    private final GitSetupProperties settings;
    private final JsonMapper json = JsonMapper.builder().build();
    public GitHubManifest(GitSetupProperties settings) { this.settings = settings; }
    @Override public Manifest create(String name) {
        URI base = valid(settings.publicUrl());
        if (!base.getPath().isEmpty() && !base.getPath().equals("/"))
            throw new GitException(NOT_CONFIGURED, "SHIPORA_PUBLIC_URL must be an origin without a path.");
        String origin = base.toString().replaceAll("/+$", "");
        URI callback = valid(settings.authorizationCallbackUrl() == null
                ? URI.create(origin + "/api/git/github/callback") : settings.authorizationCallbackUrl());
        URI registrationCallback = valid(settings.manifestCallbackUrl() == null
                ? URI.create(origin + "/api/git/setup/github/callback") : settings.manifestCallbackUrl());
        if (settings.webhooksEnabled() && !"https".equals(base.getScheme()))
            throw new GitException(NOT_CONFIGURED, "Enable GitHub webhooks only with a public HTTPS Shipora URL.");
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("name", name); manifest.put("url", origin);
        manifest.put("redirect_url", registrationCallback.toString());
        manifest.put("callback_urls", List.of(callback.toString()));
        manifest.put("hook_attributes", Map.of("url", origin + "/api/git/webhooks/github", "active", settings.webhooksEnabled()));
        manifest.put("public", true);
        manifest.put("default_permissions", Map.of("metadata", "read", "contents", "read", "members", "read"));
        manifest.put("default_events", List.of("push", "repository"));
        // Linking uses its own state/PKCE flow after installation.
        manifest.put("request_oauth_on_install", false);
        return new Manifest(json.writeValueAsString(manifest), callback);
    }
    private URI valid(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost()))))
            throw new GitException(NOT_CONFIGURED, "Use HTTPS for Shipora URLs, or HTTP localhost during development.");
        return uri;
    }
}
