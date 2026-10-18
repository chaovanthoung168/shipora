package com.shipora.git.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.nio.file.Path;

@ConfigurationProperties("shipora.github")
public record GitHubProperties(String appId, String clientId, String clientSecret, Path privateKey,
                               URI callbackUrl, String webhookSecret,
                               @DefaultValue("2026-03-10") String apiVersion, String privateKeyPem) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public GitHubProperties {}
    public GitHubProperties(String appId, String clientId, String clientSecret, Path privateKey,
                            URI callbackUrl, String webhookSecret, String apiVersion) {
        this(appId, clientId, clientSecret, privateKey, callbackUrl, webhookSecret, apiVersion, null);
    }
    @Override public String toString() { return "GitHubProperties[redacted]"; }
}
