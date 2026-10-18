package com.shipora.project.adapter.out.persistence;

import com.shipora.project.application.port.out.ProjectRepository;
import com.shipora.project.domain.exception.ProjectException;
import com.shipora.project.domain.model.Project;

import lombok.RequiredArgsConstructor;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProjectPersistenceAdapter implements ProjectRepository {

    private static final String UNIQUE_SLUG_CONSTRAINT =
            "uq_projects_organization_slug";

    private final SpringDataProjectRepository repository;

    @Override
    public Project create(Project project) {
        return flush(ProjectEntity.fromDomain(project));
    }

    @Override
    public Optional<Project> find(
            UUID organizationId,
            UUID projectId
    ) {
        return repository.findByIdAndOrganizationId(
                        projectId,
                        organizationId
                )
                .map(ProjectEntity::toDomain);
    }

    @Override
    public List<Project> findAll(
            UUID organizationId,
            int page,
            int size
    ) {
        Sort sort = Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.asc("id")
        );

        PageRequest pageable = PageRequest.of(
                page,
                size,
                sort
        );

        return repository.findAllByOrganizationId(
                        organizationId,
                        pageable
                )
                .getContent()
                .stream()
                .map(ProjectEntity::toDomain)
                .toList();
    }

    @Override
    public Project update(Project project) {
        ProjectEntity entity = repository.findByIdAndOrganizationId(
                project.getId(),
                project.getOrganizationId()
        ).orElseThrow(
                () -> new ProjectException.NotFound(project.getId())
        );

        entity.apply(project);

        return flush(entity);
    }

    @Override
    public boolean delete(
            UUID organizationId,
            UUID projectId
    ) {
        return repository.deleteScoped(
                organizationId,
                projectId
        ) > 0;
    }

    private Project flush(ProjectEntity entity) {
        try {
            return repository.saveAndFlush(entity).toDomain();
        } catch (DataIntegrityViolationException exception) {
            for (
                    Throwable cause = exception;
                    cause != null;
                    cause = cause.getCause()
            ) {
                if (cause instanceof ConstraintViolationException violation
                        && UNIQUE_SLUG_CONSTRAINT.equals(
                        violation.getConstraintName()
                )) {
                    throw new ProjectException.DuplicateSlug();
                }
            }

            throw exception;
        }
    }
}
