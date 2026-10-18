package com.shipora.project.adapter.in.web.controller;

import com.shipora.project.adapter.in.security.CurrentOrganization;
import com.shipora.project.adapter.in.web.dto.RegisterServerRequest;
import com.shipora.project.adapter.in.web.dto.ServerResponse;
import com.shipora.project.adapter.in.web.mapper.ServerWebMapper;
import com.shipora.project.application.dto.ServerResult;
import com.shipora.project.application.port.in.ServerUseCases;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/servers")
@RequiredArgsConstructor
public class ServerController {
    private final ServerUseCases useCases;
    private final CurrentOrganization currentOrganization;
    private final ServerWebMapper mapper;

    @PostMapping
    public ResponseEntity<ServerResponse> register(
            Authentication authentication,
            @Valid @RequestBody RegisterServerRequest request
    ) {
        ServerResult result = useCases.register(
                currentOrganization.require(authentication), mapper.toInput(request));
        return ResponseEntity.created(URI.create("/api/servers/" + result.id()))
                .body(mapper.toResponse(result));
    }

    @GetMapping("/{serverId}")
    public ServerResponse get(
            Authentication authentication,
            @PathVariable("serverId") UUID serverId
    ) {
        return mapper.toResponse(useCases.get(
                currentOrganization.require(authentication), serverId));
    }

    @GetMapping
    public List<ServerResponse> list(
            Authentication authentication,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return useCases.list(currentOrganization.require(authentication), page, size)
                .stream().map(mapper::toResponse).toList();
    }
}
