package com.shipora.project.adapter.out.persistence;

import com.shipora.project.domain.model.Server;
import com.shipora.project.domain.model.ServerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "servers", schema = "project")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ServerEntity {
    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(name = "ssh_port", nullable = false)
    private int sshPort;

    @Column(name = "ssh_username", nullable = false, length = 100)
    private String sshUsername;

    @Column(name = "credential_secret_ref", nullable = false, length = 255)
    private String credentialSecretRef;

    @Column(name = "ssh_host_key_fingerprint", nullable = false, length = 255)
    private String sshHostKeyFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ServerStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    static ServerEntity fromDomain(Server server) {
        return ServerEntity.builder()
                .id(server.getId())
                .organizationId(server.getOrganizationId())
                .name(server.getName())
                .host(server.getHost())
                .sshPort(server.getSshPort())
                .sshUsername(server.getSshUsername())
                .credentialSecretRef(server.getCredentialSecretRef())
                .sshHostKeyFingerprint(server.getSshHostKeyFingerprint())
                .status(server.getStatus())
                .createdAt(server.getCreatedAt())
                .build();
    }

    Server toDomain() {
        return Server.builder()
                .id(id).organizationId(organizationId).name(name).host(host)
                .sshPort(sshPort).sshUsername(sshUsername)
                .credentialSecretRef(credentialSecretRef)
                .sshHostKeyFingerprint(sshHostKeyFingerprint)
                .status(status).createdAt(createdAt).build();
    }

    void changeStatus(ServerStatus status) {
        this.status = java.util.Objects.requireNonNull(status);
    }
}
