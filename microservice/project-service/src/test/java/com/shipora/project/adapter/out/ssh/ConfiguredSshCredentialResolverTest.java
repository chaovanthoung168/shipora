package com.shipora.project.adapter.out.ssh;

import com.shipora.project.configuration.SshConnectionProperties;
import com.shipora.project.configuration.SshConnectionProperties.Target;
import com.shipora.project.domain.model.ConnectionCheckCode;
import com.shipora.project.domain.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfiguredSshCredentialResolverTest {
    @TempDir Path directory;
    private final UUID organization = UUID.randomUUID();
    private final String fingerprint = "SHA256:"
            + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
    private Path key;

    @BeforeEach
    void setUp() throws Exception {
        key = Files.writeString(directory.resolve("fixture-key"), "test fixture, not a real key");
    }

    @Test
    void resolvesOnlyAuthorizedTargetAndClearsPassphrase() {
        var resolver = new ConfiguredSshCredentialResolver(properties(List.of(target())),
                name -> "test-passphrase");
        char[] passphrase;
        try (var credential = resolver.resolve(server(organization, "127.0.0.1", "root", "test/key"))) {
            assertEquals(key, credential.target().privateKey());
            passphrase = credential.passphrase();
            assertEquals("test-passphrase", new String(passphrase));
        }
        for (char character : passphrase) assertEquals('\0', character);
    }

    @Test
    void rejectsOtherOrganizationDestinationUsernameAndReference() {
        var resolver = new ConfiguredSshCredentialResolver(properties(List.of(target())));
        for (Server candidate : List.of(
                server(UUID.randomUUID(), "127.0.0.1", "root", "test/key"),
                server(organization, "127.0.0.2", "root", "test/key"),
                server(organization, "127.0.0.1", "other", "test/key"),
                server(organization, "127.0.0.1", "root", "other/key"),
                server(organization, "example.com", "root", "test/key"))) {
            var failure = assertThrows(ConfiguredSshCredentialResolver.ResolutionFailed.class,
                    () -> resolver.resolve(candidate));
            assertEquals(ConnectionCheckCode.TARGET_NOT_AUTHORIZED, failure.code());
        }
    }

    @Test
    void rejectsMissingKeyWithoutReturningItsPath() throws Exception {
        Files.delete(key);
        var resolver = new ConfiguredSshCredentialResolver(properties(List.of(target())));
        var failure = assertThrows(ConfiguredSshCredentialResolver.ResolutionFailed.class,
                () -> resolver.resolve(server(organization, "127.0.0.1", "root", "test/key")));
        assertEquals(ConnectionCheckCode.CREDENTIAL_UNAVAILABLE, failure.code());
        assertFalse(failure.getMessage().contains(key.toString()));
    }

    @Test
    void emptyAllowlistDisablesSshChecks() {
        var resolver = new ConfiguredSshCredentialResolver(properties(List.of()));
        var failure = assertThrows(ConfiguredSshCredentialResolver.ResolutionFailed.class,
                () -> resolver.resolve(server(organization, "127.0.0.1", "root", "test/key")));
        assertEquals(ConnectionCheckCode.SSH_NOT_CONFIGURED, failure.code());
    }

    @Test
    void credentialCannotBeUsedForAnotherPortOrHostKey() {
        var resolver = new ConfiguredSshCredentialResolver(properties(List.of(target())));
        byte[] digest = new byte[32];
        digest[0] = 1;
        String differentFingerprint = "SHA256:"
                + Base64.getEncoder().withoutPadding().encodeToString(digest);
        for (Server candidate : List.of(
                Server.register(organization, "Test", "127.0.0.1", 2222, "root",
                        "test/key", fingerprint, Instant.now()),
                Server.register(organization, "Test", "127.0.0.1", 22, "root",
                        "test/key", differentFingerprint, Instant.now()))) {
            var failure = assertThrows(ConfiguredSshCredentialResolver.ResolutionFailed.class,
                    () -> resolver.resolve(candidate));
            assertEquals(ConnectionCheckCode.TARGET_NOT_AUTHORIZED, failure.code());
        }
    }

    private Target target() {
        return new Target(organization, "test/key", "127.0.0.1", 22, "root",
                "ssh-ed25519", fingerprint, key, "TEST_PASSPHRASE");
    }

    private Server server(UUID organization, String host, String username, String reference) {
        return Server.register(organization, "Test", host, 22, username,
                reference, fingerprint, Instant.now());
    }

    private SshConnectionProperties properties(List<Target> targets) {
        return new SshConnectionProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(2), 1, targets);
    }
}
