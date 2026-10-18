package com.shipora.project.application.port.in;

import com.shipora.project.application.dto.ProjectInput;
import com.shipora.project.application.dto.ProjectResult;

import java.util.List;
import java.util.UUID;

public interface ProjectUseCases {

    ProjectResult create(
            UUID organizationId,
            ProjectInput input
    );

    ProjectResult get(
            UUID organizationId,
            UUID projectId
    );

    List<ProjectResult> list(
            UUID organizationId,
            int page,
            int size
    );

    ProjectResult update(
            UUID organizationId,
            UUID projectId,
            ProjectInput input
    );

    void delete(
            UUID organizationId,
            UUID projectId
    );
}
