package com.shipora.project.adapter.in.web.mapper;

import com.shipora.project.adapter.in.web.dto.RegisterServerRequest;
import com.shipora.project.adapter.in.web.dto.ServerResponse;
import com.shipora.project.adapter.in.web.dto.ServerConnectionResponse;
import com.shipora.project.application.dto.RegisterServerInput;
import com.shipora.project.application.dto.ServerResult;
import com.shipora.project.application.dto.ServerConnectionResult;
import org.springframework.stereotype.Component;

@Component
public class ServerWebMapper {
    public ServerConnectionResponse toConnectionResponse(ServerConnectionResult result) {
        return new ServerConnectionResponse(result.serverId(), result.status(),
                result.code(), result.message(), result.checkedAt());
    }

    public RegisterServerInput toInput(RegisterServerRequest request) {
        return new RegisterServerInput(
                request.name(), request.host(), request.sshPort(),
                request.sshUsername(), request.credentialSecretRef(),
                request.sshHostKeyFingerprint());
    }

    public ServerResponse toResponse(ServerResult result) {
        return new ServerResponse(
                result.id(), result.organizationId(), result.name(), result.host(),
                result.sshPort(), result.sshUsername(), result.sshHostKeyFingerprint(),
                result.status(), result.createdAt());
    }
}
