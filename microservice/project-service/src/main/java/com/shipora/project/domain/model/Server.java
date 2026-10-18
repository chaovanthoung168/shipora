package com.shipora.project.domain.model;

import com.shipora.project.domain.exception.ServerException;
import lombok.Builder;
import lombok.Getter;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

@Getter
public final class
Server {
    private final UUID id;
    private final UUID organizationId;
    private final String name;
    private final String host;
    private final int sshPort;
    private final String sshUsername;
    private final String credentialSecretRef;
    private final String sshHostKeyFingerprint;
    private final ServerStatus status;
    private final Instant createdAt;

    @Builder
    private Server(
            UUID id, UUID organizationId, String name, String host,
            int sshPort, String sshUsername, String credentialSecretRef,
            String sshHostKeyFingerprint, ServerStatus status, Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.organizationId = Objects.requireNonNull(
                organizationId, "organizationId is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.name = requiredText(name, "Name", 150);
        this.host = validateHost(host);
        if (sshPort < 1 || sshPort > 65535) {
            throw new ServerException.Invalid("SSH port must be between 1 and 65535.");
        }
        this.sshPort = sshPort;
        this.sshUsername = requiredText(sshUsername, "SSH username", 100);
        if (this.sshUsername.chars().anyMatch(Character::isWhitespace)) {
            throw new ServerException.Invalid("SSH username must not contain whitespace.");
        }
        this.credentialSecretRef = requiredText(
                credentialSecretRef, "Credential secret reference", 255);
        this.sshHostKeyFingerprint = normalizeFingerprint(sshHostKeyFingerprint);
    }

    public static Server register(
            UUID organizationId, String name, String host, Integer sshPort,
            String sshUsername, String credentialSecretRef,
            String sshHostKeyFingerprint, Instant now
    ) {
        return Server.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .name(name)
                .host(host)
                .sshPort(sshPort == null ? 22 : sshPort)
                .sshUsername(sshUsername)
                .credentialSecretRef(credentialSecretRef)
                .sshHostKeyFingerprint(sshHostKeyFingerprint)
                .status(ServerStatus.OFFLINE)
                .createdAt(now)
                .build();
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ServerException.Invalid(field + " is required.");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new ServerException.Invalid(
                    field + " must contain at most " + maxLength + " characters.");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new ServerException.Invalid(field + " must not contain control characters.");
        }
        return normalized;
    }

    private static String validateHost(String value) {
        String host = requiredText(value, "Host", 255);
        if (host.chars().anyMatch(Character::isWhitespace)
                || host.contains("/") || host.contains("\\") || host.contains("@")
                || host.contains("?") || host.contains("#")
                || host.contains("[") || host.contains("]")) {
            throw new ServerException.Invalid(
                    "Host must be a hostname, IPv4 address, or unbracketed IPv6 address.");
        }
        try {
            String authority = host.contains(":") ? "[" + host + "]" : host;
            URI uri = URI.create("ssh://" + authority);
            if (uri.getHost() == null || uri.getPort() != -1) {
                throw new IllegalArgumentException("Invalid host.");
            }
        } catch (IllegalArgumentException exception) {
            throw new ServerException.Invalid(
                    "Host must be a valid hostname or IP address; provide the port separately.");
        }
        return host;
    }

    private static String normalizeFingerprint(String value) {
        String fingerprint = requiredText(value, "SSH host key fingerprint", 255);
        if (!fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}=?")) {
            throw new ServerException.Invalid(
                    "SSH host key fingerprint must use SHA256 format.");
        }
        String encoded = fingerprint.substring("SHA256:".length());
        byte[] digest;
        try {
            digest = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new ServerException.Invalid("SSH fingerprint contains invalid Base64.");
        }
        if (digest.length != 32) {
            throw new ServerException.Invalid("SSH fingerprint must contain a SHA256 hash.");
        }
        String canonical = Base64.getEncoder().withoutPadding().encodeToString(digest);
        String unpadded = encoded.endsWith("=")
                ? encoded.substring(0, encoded.length() - 1) : encoded;
        if (!canonical.equals(unpadded)) {
            throw new ServerException.Invalid("SSH fingerprint is not canonical Base64.");
        }
        return "SHA256:" + canonical;
    }
}
