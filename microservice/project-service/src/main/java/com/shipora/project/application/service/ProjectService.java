package com.shipora.project.application.service;

import com.shipora.project.application.dto.ProjectInput;
import com.shipora.project.application.dto.ProjectResult;
import com.shipora.project.application.port.in.ProjectUseCases;
import com.shipora.project.application.port.out.ProjectRepository;
import com.shipora.project.domain.exception.ProjectException;
import com.shipora.project.domain.model.Project;

import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
public final class ProjectService implements ProjectUseCases {

    private final ProjectRepository repository;
    private final Clock clock;

    @Override
    public ProjectResult create(
            UUID organizationId,
            ProjectInput input
    ) {
        Project project = Project.create(
                organizationId,
                input.name(),
                input.slug(),
                input.description(),
                clock.instant()
        );

        Project saved = repository.create(project);

        return ProjectResult.from(saved);
    }

    @Override
    public ProjectResult get(
            UUID organizationId,
            UUID projectId
    ) {
        Project project = requireProject(
                organizationId,
                projectId
        );

        return ProjectResult.from(project);
    }

    @Override
    public List<ProjectResult> list(
            UUID organizationId,
            int page,
            int size
    ) {
        validatePagination(page, size);

        return repository.findAll(
                        organizationId,
                        page,
                        size
                )
                .stream()
                .map(ProjectResult::from)
                .toList();
    }

    @Override
    public ProjectResult update(
            UUID organizationId,
            UUID projectId,
            ProjectInput input
    ) {
        Project existing = requireProject(
                organizationId,
                projectId
        );

        Project updated = existing.update(
                input.name(),
                input.slug(),
                input.description(),
                clock.instant()
        );

        Project saved = repository.update(updated);

        return ProjectResult.from(saved);
    }

    @Override
    public void delete(
            UUID organizationId,
            UUID projectId
    ) {
        boolean deleted = repository.delete(
                organizationId,
                projectId
        );

        if (!deleted) {
            throw new ProjectException.NotFound(projectId);
        }
    }

    private Project requireProject(
            UUID organizationId,
            UUID projectId
    ) {
        return repository.find(organizationId, projectId)
                .orElseThrow(
                        () -> new ProjectException.NotFound(projectId)
                );
    }

    private void validatePagination(int page, int size) {
        if (page < 0) {
            throw new ProjectException.Invalid(
                    "Page must be greater than or equal to zero."
            );
        }

        if (size < 1 || size > 100) {
            throw new ProjectException.Invalid(
                    "Size must be between 1 and 100."
            );
        }
    }
}