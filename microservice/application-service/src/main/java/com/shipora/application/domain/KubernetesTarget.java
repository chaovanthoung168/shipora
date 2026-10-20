package com.shipora.application.domain;

import lombok.Builder;
import lombok.Getter;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder(toBuilder = true)
public final class KubernetesTarget {
    public enum Status { UNVERIFIED, ACTIVE, ERROR }
    private final UUID id, organizationId;
    private final String name, apiServer, namespace;
    private final Credentials credentials;
    private final Status status;
    private final String checkCode;
    private final long version;
    private final Instant createdAt, updatedAt, checkedAt;
    public record Credentials(String ciphertext, String keyVersion) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
}
