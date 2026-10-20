package com.shipora.application.configuration;

import com.shipora.application.adapter.out.crypto.FileSecretCipher;
import com.shipora.application.adapter.out.crypto.EncryptedKubernetesCredentialVault;
import com.shipora.application.adapter.out.http.*;
import com.shipora.application.application.port.in.ApplicationUseCases;
import com.shipora.application.application.port.in.KubernetesTargetUseCases;
import com.shipora.application.application.port.out.*;
import com.shipora.application.application.service.ApplicationService;
import com.shipora.application.application.service.KubernetesTargetService;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
import java.time.Clock;

@Configuration
@org.springframework.context.annotation.Import(com.shipora.pipeline.PipelineConfiguration.class)
@EnableConfigurationProperties(CatalogProperties.class)
public class ApplicationConfiguration {
    @Bean Clock clock(){return Clock.systemUTC();}
    @Bean SecretCipher secretCipher(@Value("${shipora.secrets.data-directory}") Path path,SecretKeyRegistry registry){return new FileSecretCipher(path,registry);}
    @Bean(destroyMethod="close") HttpResourceCatalog resourceCatalog(CatalogProperties properties,CallerAuthorization caller){
        return new HttpResourceCatalog(properties,caller::forOrganization);
    }
    @Bean KubernetesCredentialVault kubernetesCredentialVault(SecretCipher cipher,JsonMapper json){return new EncryptedKubernetesCredentialVault(cipher,json);}
    @Bean KubernetesProbe kubernetesProbe(){return new HttpKubernetesProbe();}
    @Bean com.shipora.application.application.service.WorkerContextService workerContexts(WorkerConfigurationPort store,SecretCipher cipher,KubernetesTargetStore targets,KubernetesCredentialVault vault){
        return new com.shipora.application.application.service.WorkerContextService(store,cipher,targets,vault);
    }
    @Bean KubernetesTargetUseCases kubernetesTargets(KubernetesTargetStore store,KubernetesCredentialVault vault,KubernetesProbe probe,Clock clock){
        return new KubernetesTargetService(store,vault,probe,clock);
    }
    @Bean ApplicationUseCases applicationUseCases(ApplicationStore store,ResourceCatalog catalog,SecretCipher cipher,Clock clock,KubernetesTargetStore targets){
        return new ApplicationService(store,catalog,cipher,clock,targets);
    }
}
