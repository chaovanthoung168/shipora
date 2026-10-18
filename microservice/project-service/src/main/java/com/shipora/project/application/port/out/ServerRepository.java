package com.shipora.project.application.port.out;

import com.shipora.project.domain.model.Server;
import com.shipora.project.domain.model.ServerStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServerRepository {
    Server create(Server server);
    Server updateStatus(UUID organizationId, UUID serverId, ServerStatus status);
    Optional<Server> find(UUID organizationId, UUID serverId);
    List<Server> findAll(UUID organizationId, int page, int size);
}
