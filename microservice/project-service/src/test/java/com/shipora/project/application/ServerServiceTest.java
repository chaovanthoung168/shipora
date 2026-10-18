package com.shipora.project.application;

import com.shipora.project.application.dto.RegisterServerInput;
import com.shipora.project.application.dto.ServerResult;
import com.shipora.project.application.port.out.ServerRepository;
import com.shipora.project.application.service.ServerService;
import com.shipora.project.domain.exception.ServerException;
import com.shipora.project.domain.model.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ServerServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    // Synthetic test hash; never use it as a real server's fingerprint.
    private static final String FINGERPRINT = "SHA256:"
            + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);

    private final UUID organizationId = UUID.randomUUID();
    private ServerRepository repository;
    private ServerService service;

    @BeforeEach
    void setUp() {
        repository = mock(ServerRepository.class);
        service = new ServerService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registersOfflineServerAndDefaultsPort() {
        when(repository.create(any(Server.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ServerResult result = service.register(organizationId, input(null, FINGERPRINT));
        assertNotNull(result.id());
        assertEquals(organizationId, result.organizationId());
        assertEquals(22, result.sshPort());
        assertEquals("OFFLINE", result.status());
        assertEquals(NOW, result.createdAt());
    }

    @Test
    void rejectsInvalidFingerprintBeforeWriting() {
        assertThrows(ServerException.Invalid.class,
                () -> service.register(organizationId, input(22, "invalid")));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsInvalidPortBeforeWriting() {
        assertThrows(ServerException.Invalid.class,
                () -> service.register(organizationId, input(70000, FINGERPRINT)));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUrlOrHostWithEmbeddedPort() {
        for (String host : List.of("https://server.example.com/path", "server.example.com:22")) {
            var input = new RegisterServerInput("Development", host, 22, "deploy",
                    "server-credentials/development", FINGERPRINT);
            assertThrows(ServerException.Invalid.class,
                    () -> service.register(organizationId, input));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsUnbracketedIpv6() {
        when(repository.create(any(Server.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var input = new RegisterServerInput("Development", "::1", 22, "deploy",
                "server-credentials/development", FINGERPRINT);
        assertEquals("::1", service.register(organizationId, input).host());
    }

    @Test
    void scopesLookupToRequestingOrganization() {
        UUID serverId = UUID.randomUUID();
        UUID otherOrganization = UUID.randomUUID();
        when(repository.find(otherOrganization, serverId)).thenReturn(Optional.empty());
        assertThrows(ServerException.NotFound.class,
                () -> service.get(otherOrganization, serverId));
        verify(repository).find(otherOrganization, serverId);
    }

    @Test
    void listsApplicationResults() {
        Server server = Server.register(organizationId, "Development", "server.example.com",
                22, "deploy", "server-credentials/development", FINGERPRINT, NOW);
        when(repository.findAll(organizationId, 0, 20)).thenReturn(List.of(server));
        List<ServerResult> results = service.list(organizationId, 0, 20);
        assertEquals(1, results.size());
        assertEquals(server.getId(), results.getFirst().id());
    }

    @Test
    void rejectsInvalidPaginationBeforeReading() {
        assertThrows(ServerException.Invalid.class,
                () -> service.list(organizationId, 0, 101));
        assertThrows(ServerException.Invalid.class,
                () -> service.list(organizationId, -1, 20));
        assertThrows(ServerException.Invalid.class,
                () -> service.list(organizationId, Integer.MAX_VALUE, 100));
        verifyNoInteractions(repository);
    }

    private RegisterServerInput input(Integer port, String fingerprint) {
        return new RegisterServerInput("Development", "server.example.com", port,
                "deploy", "server-credentials/development", fingerprint);
    }
}
