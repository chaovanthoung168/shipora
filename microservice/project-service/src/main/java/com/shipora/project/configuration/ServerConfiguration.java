package com.shipora.project.configuration;

import com.shipora.project.adapter.in.transaction.TransactionalServerUseCases;
import com.shipora.project.application.port.in.ServerUseCases;
import com.shipora.project.application.port.out.ServerRepository;
import com.shipora.project.application.service.ServerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ServerConfiguration {
    @Bean
    ServerUseCases serverUseCases(ServerRepository repository, Clock clock) {
        return new TransactionalServerUseCases(new ServerService(repository, clock));
    }
}
