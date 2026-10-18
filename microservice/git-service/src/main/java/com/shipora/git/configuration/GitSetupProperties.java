package com.shipora.git.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.nio.file.Path;

@ConfigurationProperties("shipora.setup")
public record GitSetupProperties(@DefaultValue("http://localhost:8084") URI publicUrl,
                                 URI manifestCallbackUrl, URI authorizationCallbackUrl,
                                 @DefaultValue("./.shipora/git-service") Path dataDirectory,
                                 @DefaultValue("false") boolean webhooksEnabled) {}
