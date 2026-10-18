package com.shipora.project.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterServerRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 255) String host,
        @Min(1) @Max(65535) Integer sshPort,
        @NotBlank @Size(max = 100) String sshUsername,
        @NotBlank @Size(max = 255) String credentialSecretRef,
        @NotBlank
        @Pattern(regexp = "SHA256:[A-Za-z0-9+/]{43}=?",
                message = "Must be a SHA256 SSH host key fingerprint.")
        String sshHostKeyFingerprint
) {}
