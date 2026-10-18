package com.shipora.git.application.dto;

import java.time.Instant;

public record DiscoveryConnectionResult(String authorizationUrl, Instant expiresAt, String connectionToken) {
    @Override public String toString() { return "DiscoveryConnectionResult[redacted]"; }
}
