package com.shipora.project.adapter.out.ssh;

import com.shipora.project.application.port.out.ServerConnectionProbe;
import com.shipora.project.configuration.SshConnectionProperties;
import com.shipora.project.domain.exception.ServerException;
import com.shipora.project.domain.model.ConnectionCheckCode;
import com.shipora.project.domain.model.Server;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.PublicKey;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class SshjServerConnectionProbe implements ServerConnectionProbe, AutoCloseable {
    private final SshConnectionProperties properties;
    private final ConfiguredSshCredentialResolver resolver;
    private final Function<String, SSHClient> clients;
    private final ExecutorService executor;
    private final Set<SSHClient> activeClients = ConcurrentHashMap.newKeySet();

    public SshjServerConnectionProbe(
            SshConnectionProperties properties, ConfiguredSshCredentialResolver resolver
    ) {
        this(properties, resolver, SshjServerConnectionProbe::newClient);
    }

    SshjServerConnectionProbe(
            SshConnectionProperties properties, ConfiguredSshCredentialResolver resolver,
            Function<String, SSHClient> clients
    ) {
        this.properties = properties;
        this.resolver = resolver;
        this.clients = clients;
        this.executor = new ThreadPoolExecutor(
                properties.maxConcurrentChecks(), properties.maxConcurrentChecks(),
                0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().daemon(true).name("ssh-check-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public ConnectionCheckCode check(Server server) {
        AtomicReference<SSHClient> liveClient = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        Future<ConnectionCheckCode> task;
        try {
            task = executor.submit(() -> perform(server, liveClient, cancelled));
        } catch (RejectedExecutionException exception) {
            throw new ServerException.CheckBusy();
        }
        try {
            return task.get(properties.checkTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            cancelled.set(true);
            abort(liveClient.get());
            task.cancel(true);
            return ConnectionCheckCode.TIMED_OUT;
        } catch (InterruptedException exception) {
            cancelled.set(true);
            abort(liveClient.get());
            task.cancel(true);
            Thread.currentThread().interrupt();
            return ConnectionCheckCode.CHECK_FAILED;
        } catch (ExecutionException exception) {
            return ConnectionCheckCode.CHECK_FAILED;
        }
    }

    private ConnectionCheckCode perform(
            Server server, AtomicReference<SSHClient> liveClient, AtomicBoolean cancelled
    ) {
        SSHClient client = null;
        AtomicBoolean mismatch = new AtomicBoolean();
        try (ResolvedSshCredential credential = resolver.resolve(server)) {
            if (cancelled.get()) return ConnectionCheckCode.TIMED_OUT;
            var target = credential.target();
            client = clients.apply(target.hostKeyAlgorithm());
            liveClient.set(client);
            activeClients.add(client);
            client.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
            client.setTimeout(Math.toIntExact(properties.ioTimeout().toMillis()));
            HostKeyVerifier pinned = FingerprintVerifier.getInstance(server.getSshHostKeyFingerprint());
            client.addHostKeyVerifier(new HostKeyVerifier() {
                @Override
                public boolean verify(String hostname, int port, PublicKey key) {
                    boolean accepted = pinned.verify(hostname, port, key);
                    if (!accepted) mismatch.set(true);
                    return accepted;
                }

                @Override
                public List<String> findExistingAlgorithms(String hostname, int port) {
                    return List.of(target.hostKeyAlgorithm());
                }
            });
            // Connect to the authorized literal IP, and verify the host before using the key.
            if (cancelled.get()) return ConnectionCheckCode.TIMED_OUT;
            client.connect(InetAddress.ofLiteral(target.host()), target.port());
            if (cancelled.get()) return ConnectionCheckCode.TIMED_OUT;
            KeyProvider key;
            try {
                key = client.loadKeys(target.privateKey().toString(), credential.passphrase());
                key.getPrivate();
            } catch (IOException | RuntimeException exception) {
                return ConnectionCheckCode.CREDENTIAL_UNAVAILABLE;
            }
            if (cancelled.get()) return ConnectionCheckCode.TIMED_OUT;
            client.authPublickey(target.username(), key);
            return client.isAuthenticated()
                    ? ConnectionCheckCode.CONNECTED : ConnectionCheckCode.AUTHENTICATION_FAILED;
        } catch (ConfiguredSshCredentialResolver.ResolutionFailed exception) {
            return exception.code();
        } catch (UserAuthException exception) {
            return mismatch.get() ? ConnectionCheckCode.HOST_KEY_MISMATCH
                    : ConnectionCheckCode.AUTHENTICATION_FAILED;
        } catch (SocketTimeoutException exception) {
            return mismatch.get() ? ConnectionCheckCode.HOST_KEY_MISMATCH
                    : ConnectionCheckCode.TIMED_OUT;
        } catch (IOException exception) {
            return mismatch.get() ? ConnectionCheckCode.HOST_KEY_MISMATCH
                    : ConnectionCheckCode.UNREACHABLE;
        } catch (RuntimeException exception) {
            return ConnectionCheckCode.CHECK_FAILED;
        } finally {
            if (client != null) {
                closeQuietly(client);
                activeClients.remove(client);
            }
        }
    }

    private static SSHClient newClient(String hostKeyAlgorithm) {
        DefaultConfig config = new DefaultConfig();
        var algorithms = config.getKeyAlgorithms().stream()
                .filter(algorithm -> hostKeyAlgorithm.equals(algorithm.getName())).toList();
        if (algorithms.isEmpty()) {
            throw new IllegalArgumentException("Configured SSH host key algorithm is unsupported.");
        }
        config.setKeyAlgorithms(algorithms);
        return new SSHClient(config);
    }

    private static void abort(SSHClient client) {
        if (client == null) return;
        Socket socket = client.getSocket();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Cleanup must not disclose local credential or transport details.
            }
        }
    }

    private static void closeQuietly(SSHClient client) {
        try {
            client.close();
        } catch (IOException ignored) {
            abort(client);
        }
    }

    @Override
    public void close() {
        activeClients.forEach(SshjServerConnectionProbe::abort);
        executor.shutdownNow();
    }
}
