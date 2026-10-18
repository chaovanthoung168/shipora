package com.shipora.project.adapter.in.web.controller;

import com.shipora.project.adapter.in.security.CurrentOrganization;
import com.shipora.project.adapter.in.web.dto.ServerConnectionResponse;
import com.shipora.project.adapter.in.web.mapper.ServerWebMapper;
import com.shipora.project.application.port.in.ServerConnectionUseCases;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/servers/{serverId}/connection-checks")
@RequiredArgsConstructor
public class ServerConnectionController {
    private final ServerConnectionUseCases useCases;
    private final CurrentOrganization currentOrganization;
    private final ServerWebMapper mapper;

    @PostMapping
    public ServerConnectionResponse check(
            Authentication authentication, @PathVariable("serverId") UUID serverId
    ) {
        return mapper.toConnectionResponse(useCases.check(
                currentOrganization.require(authentication), serverId));
    }
}
