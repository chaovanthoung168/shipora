package com.shipora.project.adapter.out.persistence;

import com.shipora.project.application.port.out.ServerRepository;
import com.shipora.project.domain.model.Server;
import com.shipora.project.domain.model.ServerStatus;
import com.shipora.project.domain.exception.ServerException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ServerPersistenceAdapter implements ServerRepository {
    private final SpringDataServerRepository repository;
    private final EntityManager entityManager;

    @Override
    public Server create(Server server) {
        ServerEntity entity = ServerEntity.fromDomain(server);
        // Registration inserts a new row; it is not an upsert.
        entityManager.persist(entity);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional
    public Server updateStatus(UUID organizationId, UUID serverId, ServerStatus status) {
        ServerEntity entity = repository.findByIdAndOrganizationId(serverId, organizationId)
                .orElseThrow(() -> new ServerException.NotFound(serverId));
        entity.changeStatus(status);
        entityManager.flush();
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Server> find(UUID organizationId, UUID serverId) {
        return repository.findByIdAndOrganizationId(serverId, organizationId)
                .map(ServerEntity::toDomain);
    }

    @Override
    public List<Server> findAll(UUID organizationId, int page, int size) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
        return repository.findAllByOrganizationId(
                        organizationId, PageRequest.of(page, size, sort))
                .stream().map(ServerEntity::toDomain).toList();
    }
}
