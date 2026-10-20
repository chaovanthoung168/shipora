package com.shipora.git.configuration;

import com.shipora.git.adapter.in.web.SignedWebhookHandler;
import com.shipora.git.adapter.out.github.GitHubClient;
import com.shipora.git.application.port.in.*;
import com.shipora.git.application.port.out.*;
import com.shipora.git.application.service.GitService;
import com.shipora.git.application.service.GitConnectionService;
import com.shipora.git.application.service.GitHubSetupService;
import com.shipora.git.adapter.out.github.GitHubManifest;
import com.shipora.git.adapter.out.persistence.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.time.Clock;

@Configuration
@org.springframework.context.annotation.Import(com.shipora.pipeline.PipelineConfiguration.class)
@EnableConfigurationProperties({GitHubProperties.class, GitSetupProperties.class})
public class GitConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean CredentialCipher credentialCipher(GitSetupProperties properties) {
        return new FileCredentialCipher(properties.dataDirectory());
    }
    @Bean GitHubCredentialsSource gitHubCredentialsSource(GitHubSetupStore store, GitHubProperties properties) {
        return new GitHubCredentialsSource(store, properties);
    }
    @Bean(destroyMethod = "close") GitHubClient gitHubClient(GitHubCredentialsSource source, Clock clock) {
        return new GitHubClient(source, clock);
    }
    @Bean SetupManifest setupManifest(GitSetupProperties properties) { return new GitHubManifest(properties); }
    @Bean GitHubSetupUseCases gitHubSetupUseCases(GitHubSetupStore store, GitHubRegistration registration,
            SetupManifest manifest, GitHubCredentialsSource source, Clock clock) {
        return new GitHubSetupService(store, registration, manifest, source::status, clock);
    }
    @Bean GitUseCases gitUseCases(GitStore store, GitProvider provider, Clock clock) { return new GitService(store, provider, clock); }
    @Bean com.shipora.git.application.service.WorkerSourceService workerSources(GitStore store,GitProvider provider){return new com.shipora.git.application.service.WorkerSourceService(store,provider);}
    @Bean GitConnectionUseCases gitConnectionUseCases(ConnectionStore connectionStore, GitStore store, GitProvider provider, Clock clock) {
        return new GitConnectionService(connectionStore, store, provider, clock);
    }
    @Bean WebhookUseCases webhookUseCases(GitStore store, Clock clock) {
        return delivery -> store.recordWebhook(delivery, clock.instant());
    }
    @Bean SignedWebhookHandler signedWebhookHandler(GitHubCredentialsSource source, WebhookUseCases useCases) {
        return new SignedWebhookHandler(() -> source.get().webhookSecret(), useCases);
    }
}
