package com.shipora.project.adapter.out.ssh;

import com.shipora.project.configuration.SshConnectionProperties;
import com.shipora.project.configuration.SshConnectionProperties.Target;
import com.shipora.project.domain.model.ConnectionCheckCode;
import com.shipora.project.domain.model.Server;

import java.net.InetAddress;
import java.nio.file.Files;
import java.util.function.Function;

public final class ConfiguredSshCredentialResolver {
    private final SshConnectionProperties properties;
    private final Function<String, String> environment;

    public ConfiguredSshCredentialResolver(SshConnectionProperties properties) {
        this(properties, System::getenv);
    }

    ConfiguredSshCredentialResolver(
            SshConnectionProperties properties, Function<String, String> environment
    ) {
        this.properties = properties;
        this.environment = environment;
    }

    ResolvedSshCredential resolve(Server server) {
        if (properties.targets().isEmpty()) {
            throw new ResolutionFailed(ConnectionCheckCode.SSH_NOT_CONFIGURED);
        }
        // Request data cannot select an arbitrary key file or destination.
        Target target = properties.targets().stream()
                .filter(candidate -> matches(candidate, server))
                .findFirst()
                .orElseThrow(() -> new ResolutionFailed(ConnectionCheckCode.TARGET_NOT_AUTHORIZED));
        if (!Files.isRegularFile(target.privateKey()) || !Files.isReadable(target.privateKey())) {
            throw new ResolutionFailed(ConnectionCheckCode.CREDENTIAL_UNAVAILABLE);
        }
        String passphrase = target.passphraseEnvironmentVariable().isEmpty()
                ? null : environment.apply(target.passphraseEnvironmentVariable());
        return new ResolvedSshCredential(target,
                passphrase == null ? new char[0] : passphrase.toCharArray());
    }

    private boolean matches(Target target, Server server) {
        if (!target.organizationId().equals(server.getOrganizationId())
                || !target.credentialSecretRef().equals(server.getCredentialSecretRef())
                || target.port() != server.getSshPort()
                || !target.username().equals(server.getSshUsername())
                || !target.hostKeyFingerprint().equals(server.getSshHostKeyFingerprint())) {
            return false;
        }
        try {
            return InetAddress.ofLiteral(target.host())
                    .equals(InetAddress.ofLiteral(server.getHost()));
        } catch (IllegalArgumentException exception) {
            // This implementation authorizes configured IP literals, without DNS resolution.
            return false;
        }
    }

    static final class ResolutionFailed extends RuntimeException {
        private final ConnectionCheckCode code;

        ResolutionFailed(ConnectionCheckCode code) {
            super(code.message());
            this.code = code;
        }

        ConnectionCheckCode code() {
            return code;
        }
    }
}
