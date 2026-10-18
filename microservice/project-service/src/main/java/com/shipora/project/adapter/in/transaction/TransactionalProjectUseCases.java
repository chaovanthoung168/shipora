package com.shipora.project.adapter.in.transaction;

import com.shipora.project.application.dto.ProjectInput;
import com.shipora.project.application.dto.ProjectResult;
import com.shipora.project.application.port.in.ProjectUseCases;

import lombok.RequiredArgsConstructor;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Transactional
@RequiredArgsConstructor
public class TransactionalProjectUseCases implements ProjectUseCases {

    private final ProjectUseCases delegate;

    @Override
    public ProjectResult create(
            UUID organizationId,
            ProjectInput input
    ) {
        return delegate.create(organizationId, input);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectResult get(
            UUID organizationId,
            UUID projectId
    ) {
        return delegate.get(organizationId, projectId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectResult> list(
            UUID organizationId,
            int page,
            int size
    ) {
        return delegate.list(organizationId, page, size);
    }

    @Override
    public ProjectResult update(
            UUID organizationId,
            UUID projectId,
            ProjectInput input
    ) {
        return delegate.update(
                organizationId,
                projectId,
                input
        );
    }

    @Override
    public void delete(
            UUID organizationId,
            UUID projectId
    ) {
        delegate.delete(organizationId, projectId);
    }
}
