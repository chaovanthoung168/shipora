package com.shipora.project.application.port.in;

import com.shipora.project.application.dto.RegisterServerInput;
import com.shipora.project.application.dto.ServerResult;
import java.util.List;
import java.util.UUID;

public interface ServerUseCases {
    ServerResult register(UUID organizationId, RegisterServerInput input);
    ServerResult get(UUID organizationId, UUID serverId);
    List<ServerResult> list(UUID organizationId, int page, int size);
}
