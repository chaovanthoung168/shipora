package com.shipora.project.configuration;

import com.shipora.project.adapter.out.ssh.ConfiguredSshCredentialResolver;
import com.shipora.project.adapter.out.ssh.SshjServerConnectionProbe;
import com.shipora.project.application.port.in.ServerConnectionUseCases;
import com.shipora.project.application.port.out.ServerConnectionProbe;
import com.shipora.project.application.port.out.ServerRepository;
import com.shipora.project.application.service.ServerConnectionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(SshConnectionProperties.class)
public class ServerConnectionConfiguration {
    @Bean
    ConfiguredSshCredentialResolver sshCredentialResolver(SshConnectionProperties properties) {
        return new ConfiguredSshCredentialResolver(properties);
    }

    @Bean(destroyMethod = "close")
    SshjServerConnectionProbe sshConnectionProbe(
            SshConnectionProperties properties, ConfiguredSshCredentialResolver resolver
    ) {
        return new SshjServerConnectionProbe(properties, resolver);
    }

    @Bean
    ServerConnectionUseCases serverConnectionUseCases(
            ServerRepository repository, ServerConnectionProbe probe, Clock clock
    ) {
        return new ServerConnectionService(repository, probe, clock);
    }
}
