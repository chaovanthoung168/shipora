package com.shipora.project.application.port.out;

import com.shipora.project.domain.model.Project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {

    Project create(Project project);

    Optional<Project> find(
            UUID organizationId,
            UUID projectId
    );

    List<Project> findAll(
            UUID organizationId,
            int page,
            int size
    );

    Project update(Project project);

    boolean delete(
            UUID organizationId,
            UUID projectId
    );
}