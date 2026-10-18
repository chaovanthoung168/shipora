package com.shipora.project.adapter.out.ssh;

import com.shipora.project.configuration.SshConnectionProperties;
import com.shipora.project.configuration.SshConnectionProperties.Target;
import com.shipora.project.domain.exception.ServerException;
import com.shipora.project.domain.model.ConnectionCheckCode;
import com.shipora.project.domain.model.Server;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshjServerConnectionProbeTest {
    @TempDir Path directory;
    private final UUID organization = UUID.randomUUID();
    private Path path;
    private KeyPair keyPair;
    private String fingerprint;
    private SSHClient client;
    private KeyProvider key;
    private Server server;

    @BeforeEach
    void setUp() throws Exception {
        path = Files.writeString(directory.resolve("fixture-key"), "test fixture, not a real key");
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(
                        new Buffer.PlainBuffer().putPublicKey(keyPair.getPublic()).getCompactData()));
        server = Server.register(organization, "Test", "127.0.0.1", 22, "root",
                "test/key", fingerprint, Instant.now());
        client = mock(SSHClient.class);
        key = mock(KeyProvider.class);
        when(client.loadKeys(anyString(), any(char[].class))).thenReturn(key);
        when(key.getPrivate()).thenReturn(keyPair.getPrivate());
        when(client.isAuthenticated()).thenReturn(true);
    }

    @Test
    void authenticatesWithPinnedKeyAndNeverStartsCommandSession() throws Exception {
        try (var probe = probe(Duration.ofSeconds(2))) {
            assertEquals(ConnectionCheckCode.CONNECTED, probe.check(server));
            ArgumentCaptor<HostKeyVerifier> verifier = ArgumentCaptor.forClass(HostKeyVerifier.class);
            verify(client).addHostKeyVerifier(verifier.capture());
            assertTrue(verifier.getValue().verify("127.0.0.1", 22, keyPair.getPublic()));
            var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            assertFalse(verifier.getValue().verify("127.0.0.1", 22, other.getPublic()));
            verify(client).authPublickey("root", key);
            verify(client, never()).startSession();
            verify(client).close();
        }
    }

    @Test
    void hostKeyMismatchStopsBeforeLoadingOrSendingCredential() throws Exception {
        AtomicReference<HostKeyVerifier> verifier = new AtomicReference<>();
        doAnswer(invocation -> {
            verifier.set(invocation.getArgument(0));
            return null;
        }).when(client).addHostKeyVerifier(any(HostKeyVerifier.class));
        var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        doAnswer(invocation -> {
            assertFalse(verifier.get().verify("127.0.0.1", 22, other.getPublic()));
            throw new IOException("internal transport detail");
        }).when(client).connect(any(InetAddress.class), eq(22));
        try (var probe = probe(Duration.ofSeconds(2))) {
            assertEquals(ConnectionCheckCode.HOST_KEY_MISMATCH, probe.check(server));
            verify(client, never()).loadKeys(anyString(), any(char[].class));
            verify(client, never()).authPublickey(anyString(), any(KeyProvider.class));
        }
    }

    @Test
    void authenticationFailureUsesSanitizedCode() throws Exception {
        doThrow(new UserAuthException("private internal error"))
                .when(client).authPublickey("root", key);
        try (var probe = probe(Duration.ofSeconds(2))) {
            var result = probe.check(server);
            assertEquals(ConnectionCheckCode.AUTHENTICATION_FAILED, result);
            assertFalse(result.message().contains("private internal error"));
        }
    }

    @Test
    void unreadableKeyNeverAttemptsAuthentication() throws Exception {
        when(client.loadKeys(anyString(), any(char[].class)))
                .thenThrow(new IOException("sensitive local path"));
        try (var probe = probe(Duration.ofSeconds(2))) {
            assertEquals(ConnectionCheckCode.CREDENTIAL_UNAVAILABLE, probe.check(server));
            verify(client, never()).authPublickey(anyString(), any(KeyProvider.class));
        }
    }

    @Test
    void deadlineClosesSocketAndInterruptsWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Socket socket = mock(Socket.class);
        when(client.getSocket()).thenReturn(socket);
        doAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(client).connect(any(InetAddress.class), eq(22));
        try (var probe = probe(Duration.ofMillis(500))) {
            try {
                assertEquals(ConnectionCheckCode.TIMED_OUT, probe.check(server));
                verify(socket).close();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void saturatedCapacityRejectsRatherThanQueueing() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(client).connect(any(InetAddress.class), eq(22));
        try (var probe = probe(Duration.ofSeconds(5));
             var caller = Executors.newSingleThreadExecutor()) {
            Future<ConnectionCheckCode> first = caller.submit(() -> probe.check(server));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(ServerException.CheckBusy.class, () -> probe.check(server));
            } finally {
                release.countDown();
            }
            assertEquals(ConnectionCheckCode.CONNECTED, first.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelledCheckCannotAuthenticateAfterDelayedConnectReturns() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(invocation -> {
            while (release.getCount() != 0) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Simulate a transport operation that returns late despite interruption.
                }
            }
            return null;
        }).when(client).connect(any(InetAddress.class), eq(22));
        doAnswer(invocation -> {
            closed.countDown();
            return null;
        }).when(client).close();
        try (var probe = probe(Duration.ofMillis(500))) {
            try {
                assertEquals(ConnectionCheckCode.TIMED_OUT, probe.check(server));
            } finally {
                release.countDown();
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            verify(client, never()).loadKeys(anyString(), any(char[].class));
            verify(client, never()).authPublickey(anyString(), any(KeyProvider.class));
        }
    }

    private SshjServerConnectionProbe probe(Duration timeout) {
        var target = new Target(organization, "test/key", "127.0.0.1", 22,
                "root", "ssh-ed25519", fingerprint, path, "");
        var properties = new SshConnectionProperties(
                Duration.ofSeconds(1), Duration.ofSeconds(1), timeout, 1, List.of(target));
        return new SshjServerConnectionProbe(
                properties, new ConfiguredSshCredentialResolver(properties), algorithm -> client);
    }
}
