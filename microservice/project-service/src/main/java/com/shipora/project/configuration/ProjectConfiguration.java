package com.shipora.project.configuration;

import com.shipora.project.adapter.in.transaction.TransactionalProjectUseCases;
import com.shipora.project.application.port.in.ProjectUseCases;
import com.shipora.project.application.port.out.ProjectRepository;
import com.shipora.project.application.service.ProjectService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@org.springframework.context.annotation.Import(com.shipora.pipeline.PipelineConfiguration.class)
public class ProjectConfiguration {

    @Bean
    Clock projectClock() {
        return Clock.systemUTC();
    }

    @Bean
    ProjectUseCases projectUseCases(
            ProjectRepository repository,
            Clock clock
    ) {
        ProjectService service = new ProjectService(
                repository,
                clock
        );

        return new TransactionalProjectUseCases(service);
    }
}
