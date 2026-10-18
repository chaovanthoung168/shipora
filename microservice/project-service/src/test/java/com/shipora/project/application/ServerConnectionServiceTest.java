package com.shipora.project.application;

import com.shipora.project.application.port.out.ServerConnectionProbe;
import com.shipora.project.application.port.out.ServerRepository;
import com.shipora.project.application.service.ServerConnectionService;
import com.shipora.project.domain.exception.ServerException;
import com.shipora.project.domain.model.ConnectionCheckCode;
import com.shipora.project.domain.model.Server;
import com.shipora.project.domain.model.ServerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServerConnectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private final UUID organization = UUID.randomUUID();
    private ServerRepository repository;
    private ServerConnectionProbe probe;
    private ServerConnectionService service;
    private Server server;

    @BeforeEach
    void setUp() {
        repository = mock(ServerRepository.class);
        probe = mock(ServerConnectionProbe.class);
        service = new ServerConnectionService(repository, probe, Clock.fixed(NOW, ZoneOffset.UTC));
        server = Server.register(organization, "Test", "127.0.0.1", 22, "deploy", "test/key",
                "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]), NOW);
        when(repository.find(organization, server.getId())).thenReturn(Optional.of(server));
    }

    @Test
    void successfulProbePersistsActiveStatusAndReturnsSafeResult() {
        when(probe.check(server)).thenReturn(ConnectionCheckCode.CONNECTED);
        var result = service.check(organization, server.getId());
        assertEquals("ACTIVE", result.status());
        assertEquals("CONNECTED", result.code());
        assertEquals(NOW, result.checkedAt());
        verify(repository).updateStatus(organization, server.getId(), ServerStatus.ACTIVE);
    }

    @Test
    void failureCodesPersistCorrespondingStatuses() {
        for (var code : new ConnectionCheckCode[]{
                ConnectionCheckCode.HOST_KEY_MISMATCH,
                ConnectionCheckCode.UNREACHABLE,
                ConnectionCheckCode.CREDENTIAL_UNAVAILABLE}) {
            when(probe.check(server)).thenReturn(code);
            var result = service.check(organization, server.getId());
            assertEquals(code.status().name(), result.status());
            assertEquals(code.message(), result.message());
            verify(repository).updateStatus(organization, server.getId(), code.status());
            clearInvocations(repository);
        }
    }

    @Test
    void anotherOrganizationCannotProbeTheServer() {
        UUID otherOrganization = UUID.randomUUID();
        when(repository.find(otherOrganization, server.getId())).thenReturn(Optional.empty());
        assertThrows(ServerException.NotFound.class,
                () -> service.check(otherOrganization, server.getId()));
        verifyNoInteractions(probe);
        verify(repository, never()).updateStatus(any(), any(), any());
    }

    @Test
    void capacityFailureDoesNotOverwriteStatusAndReleasesServerGuard() {
        when(probe.check(server)).thenThrow(new ServerException.CheckBusy())
                .thenReturn(ConnectionCheckCode.CONNECTED);
        assertThrows(ServerException.CheckBusy.class,
                () -> service.check(organization, server.getId()));
        verify(repository, never()).updateStatus(any(), any(), any());
        assertEquals("ACTIVE", service.check(organization, server.getId()).status());
    }

    @Test
    void concurrentChecksForSameServerAreRejected() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(probe.check(server)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out.");
            return ConnectionCheckCode.CONNECTED;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> first = executor.submit(() -> service.check(organization, server.getId()));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(ServerException.CheckBusy.class,
                        () -> service.check(organization, server.getId()));
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        verify(probe, times(1)).check(server);
    }
}
