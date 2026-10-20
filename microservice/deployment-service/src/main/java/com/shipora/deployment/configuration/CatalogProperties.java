package com.shipora.deployment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;

@ConfigurationProperties("shipora.catalog")
public record CatalogProperties(URI applicationUrl,URI projectUrl,URI gitUrl) {}
