package com.shipora.project.adapter.in.transaction;

import com.shipora.project.application.dto.RegisterServerInput;
import com.shipora.project.application.dto.ServerResult;
import com.shipora.project.application.port.in.ServerUseCases;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Transactional
@RequiredArgsConstructor
public class TransactionalServerUseCases implements ServerUseCases {
    private final ServerUseCases delegate;

    @Override
    public ServerResult register(UUID organizationId, RegisterServerInput input) {
        return delegate.register(organizationId, input);
    }

    @Override
    @Transactional(readOnly = true)
    public ServerResult get(UUID organizationId, UUID serverId) {
        return delegate.get(organizationId, serverId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServerResult> list(UUID organizationId, int page, int size) {
        return delegate.list(organizationId, page, size);
    }
}
