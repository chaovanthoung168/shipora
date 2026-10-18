package com.shipora.git.application.dto;

import java.time.Instant;

public record ConnectionResult(String authorizationUrl, Instant expiresAt) {}
