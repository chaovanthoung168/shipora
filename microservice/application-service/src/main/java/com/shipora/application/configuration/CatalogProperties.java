package com.shipora.application.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;

@ConfigurationProperties("shipora.catalog")
public record CatalogProperties(@DefaultValue("http://localhost:8081") URI projectUrl,
                                @DefaultValue("http://localhost:8084") URI gitUrl) {}
