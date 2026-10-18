package com.shipora.project.adapter.in.web.mapper;

import com.shipora.project.adapter.in.web.dto.CreateProjectRequest;
import com.shipora.project.adapter.in.web.dto.ProjectResponse;
import com.shipora.project.adapter.in.web.dto.UpdateProjectRequest;
import com.shipora.project.application.dto.ProjectInput;
import com.shipora.project.application.dto.ProjectResult;

import org.springframework.stereotype.Component;

@Component
public class ProjectWebMapper {

    public ProjectInput toInput(CreateProjectRequest request) {
        return new ProjectInput(
                request.name(),
                request.slug(),
                request.description()
        );
    }

    public ProjectInput toInput(UpdateProjectRequest request) {
        return new ProjectInput(
                request.name(),
                request.slug(),
                request.description()
        );
    }

    public ProjectResponse toResponse(ProjectResult result) {
        return new ProjectResponse(
                result.id(),
                result.organizationId(),
                result.name(),
                result.slug(),
                result.description(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}
