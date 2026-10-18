package com.shipora.project.application.dto;

public record RegisterServerInput(
        String name,
        String host,
        Integer sshPort,
        String sshUsername,
        String credentialSecretRef,
        String sshHostKeyFingerprint
) {}
