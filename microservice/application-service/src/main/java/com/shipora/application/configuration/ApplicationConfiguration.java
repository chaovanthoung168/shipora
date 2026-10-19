package com.shipora.application.configuration;

import com.shipora.application.adapter.out.crypto.FileSecretCipher;
import com.shipora.application.adapter.out.http.*;
import com.shipora.application.application.port.in.ApplicationUseCases;
import com.shipora.application.application.port.out.*;
import com.shipora.application.application.service.ApplicationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
public class ApplicationConfiguration {
    @Bean Clock clock(){return Clock.systemUTC();}
    @Bean SecretCipher secretCipher(@Value("${shipora.secrets.data-directory}") Path path,SecretKeyRegistry registry){return new FileSecretCipher(path,registry);}
    @Bean(destroyMethod="close") HttpResourceCatalog resourceCatalog(CatalogProperties properties,CallerAuthorization caller){
        return new HttpResourceCatalog(properties,caller::forOrganization);
    }
    @Bean ApplicationUseCases applicationUseCases(ApplicationStore store,ResourceCatalog catalog,SecretCipher cipher,Clock clock){
        return new ApplicationService(store,catalog,cipher,clock);
    }
}
