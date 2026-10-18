package com.shipora.project.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ConfigurationProperties(prefix = "shipora.ssh")
public record SshConnectionProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("5s") Duration ioTimeout,
        @DefaultValue("15s") Duration checkTimeout,
        @DefaultValue("2") int maxConcurrentChecks,
        List<Target> targets
) {
    public SshConnectionProperties {
        validateTimeout(connectTimeout, "connect-timeout");
        validateTimeout(ioTimeout, "io-timeout");
        validateTimeout(checkTimeout, "check-timeout");
        if (maxConcurrentChecks < 1 || maxConcurrentChecks > 8) {
            throw new IllegalArgumentException("SSH max-concurrent-checks must be between 1 and 8.");
        }
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    private static void validateTimeout(Duration timeout, String name) {
        if (timeout == null || timeout.toMillis() < 100 || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("SSH " + name + " must be between 100ms and 1m.");
        }
    }

    public record Target(
            UUID organizationId,
            String credentialSecretRef,
            String host,
            @DefaultValue("22") int port,
            String username,
            @DefaultValue("ssh-ed25519") String hostKeyAlgorithm,
            String hostKeyFingerprint,
            Path privateKey,
            String passphraseEnvironmentVariable
    ) {
        public Target {
            Objects.requireNonNull(organizationId, "SSH target organization-id is required.");
            Objects.requireNonNull(privateKey, "SSH target private-key is required.");
            credentialSecretRef = required(credentialSecretRef, "credential-secret-ref");
            username = required(username, "username");
            hostKeyAlgorithm = required(hostKeyAlgorithm, "host-key-algorithm");
            host = InetAddress.ofLiteral(required(host, "host")).getHostAddress();
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("SSH target port must be between 1 and 65535.");
            }
            hostKeyFingerprint = required(hostKeyFingerprint, "host-key-fingerprint");
            if (!hostKeyFingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) {
                throw new IllegalArgumentException("SSH target fingerprint must be unpadded SHA256 format.");
            }
            passphraseEnvironmentVariable = passphraseEnvironmentVariable == null
                    ? "" : passphraseEnvironmentVariable.strip();
            if (!passphraseEnvironmentVariable.isEmpty()
                    && !passphraseEnvironmentVariable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("SSH passphrase environment-variable name is invalid.");
            }
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("SSH target " + name + " is required.");
            }
            return value.strip();
        }
    }
}
