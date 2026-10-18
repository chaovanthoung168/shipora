package com.shipora.project.adapter.in.web.controller;

import com.shipora.project.adapter.in.security.CurrentOrganization;
import com.shipora.project.adapter.in.web.dto.CreateProjectRequest;
import com.shipora.project.adapter.in.web.dto.ProjectResponse;
import com.shipora.project.adapter.in.web.dto.UpdateProjectRequest;
import com.shipora.project.adapter.in.web.mapper.ProjectWebMapper;
import com.shipora.project.application.dto.ProjectResult;
import com.shipora.project.application.port.in.ProjectUseCases;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectUseCases useCases;
    private final CurrentOrganization currentOrganization;
    private final ProjectWebMapper mapper;

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            Authentication authentication,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        UUID organizationId =
                currentOrganization.require(authentication);

        ProjectResult result = useCases.create(
                organizationId,
                mapper.toInput(request)
        );

        URI location = URI.create(
                "/api/projects/" + result.id()
        );

        return ResponseEntity
                .created(location)
                .body(mapper.toResponse(result));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(
            Authentication authentication,
            @PathVariable("projectId") UUID projectId
    ) {
        ProjectResult result = useCases.get(
                currentOrganization.require(authentication),
                projectId
        );

        return mapper.toResponse(result);
    }

    @GetMapping
    public List<ProjectResponse> list(
            Authentication authentication,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return useCases.list(
                        currentOrganization.require(authentication),
                        page,
                        size
                )
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @PutMapping("/{projectId}")
    public ProjectResponse update(
            Authentication authentication,
            @PathVariable("projectId") UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        ProjectResult result = useCases.update(
                currentOrganization.require(authentication),
                projectId,
                mapper.toInput(request)
        );

        return mapper.toResponse(result);
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable("projectId") UUID projectId
    ) {
        useCases.delete(
                currentOrganization.require(authentication),
                projectId
        );

        return ResponseEntity.noContent().build();
    }
}