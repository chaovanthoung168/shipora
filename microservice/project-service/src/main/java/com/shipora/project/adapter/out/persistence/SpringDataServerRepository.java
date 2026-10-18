package com.shipora.project.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataServerRepository extends JpaRepository<ServerEntity, UUID> {
    Optional<ServerEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    List<ServerEntity> findAllByOrganizationId(UUID organizationId, Pageable pageable);
}
