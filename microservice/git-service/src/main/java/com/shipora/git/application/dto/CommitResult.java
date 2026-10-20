package com.shipora.git.application.dto;

import java.time.Instant;

public record CommitResult(String sha, String message, String authorName, Instant committedAt) {}
