package com.shipora.deployment.configuration;

import com.shipora.deployment.adapter.out.http.*;
import com.shipora.deployment.application.port.in.DeploymentUseCases;
import com.shipora.deployment.application.port.out.*;
import com.shipora.deployment.application.service.DeploymentService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@org.springframework.context.annotation.Import(com.shipora.pipeline.PipelineConfiguration.class)
@EnableConfigurationProperties(CatalogProperties.class)
public class DeploymentConfiguration {
    @Bean(destroyMethod="close") HttpDeploymentCatalog deploymentCatalog(CatalogProperties properties,CallerAuthorization caller){return new HttpDeploymentCatalog(properties,caller::forOrganization);}
    @Bean DeploymentUseCases deployments(DeploymentStore store,DeploymentCatalog catalog){return new DeploymentService(store,catalog);}
}
