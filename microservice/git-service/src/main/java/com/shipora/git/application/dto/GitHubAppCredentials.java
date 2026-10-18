package com.shipora.git.application.dto;

import java.net.URI;

public record GitHubAppCredentials(String appId, String slug, String clientId, String clientSecret,
                                   String privateKeyPem, String webhookSecret, URI callbackUrl) {
    public String installationUrl() { return "https://github.com/apps/" + slug + "/installations/new"; }
    @Override public String toString() { return "GitHubAppCredentials[redacted]"; }
}
