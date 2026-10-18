package com.shipora.project.application.service;

import com.shipora.project.application.dto.RegisterServerInput;
import com.shipora.project.application.dto.ServerResult;
import com.shipora.project.application.port.in.ServerUseCases;
import com.shipora.project.application.port.out.ServerRepository;
import com.shipora.project.domain.exception.ServerException;
import com.shipora.project.domain.model.Server;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
public final class ServerService implements ServerUseCases {
    private final ServerRepository repository;
    private final Clock clock;

    @Override
    public ServerResult register(UUID organizationId, RegisterServerInput input) {
        Server server = Server.register(
                organizationId, input.name(), input.host(), input.sshPort(),
                input.sshUsername(), input.credentialSecretRef(),
                input.sshHostKeyFingerprint(), clock.instant());
        return ServerResult.from(repository.create(server));
    }

    @Override
    public ServerResult get(UUID organizationId, UUID serverId) {
        Server server = repository.find(organizationId, serverId)
                .orElseThrow(() -> new ServerException.NotFound(serverId));
        return ServerResult.from(server);
    }

    @Override
    public List<ServerResult> list(UUID organizationId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ServerException.Invalid(
                    "Page must be non-negative and size must be between 1 and 100.");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ServerException.Invalid("Requested page offset is too large.");
        }
        return repository.findAll(organizationId, page, size)
                .stream().map(ServerResult::from).toList();
    }
}
