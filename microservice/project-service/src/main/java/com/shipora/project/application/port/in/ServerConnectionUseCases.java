package com.shipora.project.application.port.in;

import com.shipora.project.application.dto.ServerConnectionResult;
import java.util.UUID;

public interface ServerConnectionUseCases {
    ServerConnectionResult check(UUID organizationId, UUID serverId);
}
