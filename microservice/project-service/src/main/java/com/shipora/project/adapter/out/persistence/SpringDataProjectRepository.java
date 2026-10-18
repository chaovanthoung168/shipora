package com.shipora.project.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataProjectRepository
        extends JpaRepository<ProjectEntity, UUID> {

    Optional<ProjectEntity> findByIdAndOrganizationId(
            UUID id,
            UUID organizationId
    );

    Page<ProjectEntity> findAllByOrganizationId(
            UUID organizationId,
            Pageable pageable
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            delete from ProjectEntity p
            where p.id = :projectId
              and p.organizationId = :organizationId
            """)
    int deleteScoped(
            @Param("organizationId") UUID organizationId,
            @Param("projectId") UUID projectId
    );
}
