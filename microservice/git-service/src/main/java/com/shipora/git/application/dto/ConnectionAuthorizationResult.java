package com.shipora.git.application.dto;

import java.time.Instant;

public record ConnectionAuthorizationResult(boolean authorized, int availableAccounts,
                                            String installationUrl, Instant expiresAt) {}
