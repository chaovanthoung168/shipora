package com.shipora.project.application.service;

import com.shipora.project.application.dto.ServerConnectionResult;
import com.shipora.project.application.port.in.ServerConnectionUseCases;
import com.shipora.project.application.port.out.ServerConnectionProbe;
import com.shipora.project.application.port.out.ServerRepository;
import com.shipora.project.domain.exception.ServerException;
import com.shipora.project.domain.model.ConnectionCheckCode;
import com.shipora.project.domain.model.Server;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public final class ServerConnectionService implements ServerConnectionUseCases {
    private final ServerRepository repository;
    private final ServerConnectionProbe probe;
    private final Clock clock;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    @Override
    public ServerConnectionResult check(UUID organizationId, UUID serverId) {
        Server server = repository.find(organizationId, serverId)
                .orElseThrow(() -> new ServerException.NotFound(serverId));
        if (!inFlight.add(serverId)) {
            throw new ServerException.CheckBusy();
        }
        try {
            // There is no outer database transaction around network I/O.
            ConnectionCheckCode code = probe.check(server);
            repository.updateStatus(organizationId, serverId, code.status());
            return new ServerConnectionResult(
                    serverId, code.status().name(), code.name(),
                    code.message(), clock.instant());
        } finally {
            inFlight.remove(serverId);
        }
    }
}
