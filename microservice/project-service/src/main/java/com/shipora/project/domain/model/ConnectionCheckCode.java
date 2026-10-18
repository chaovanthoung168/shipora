package com.shipora.project.domain.model;

public enum ConnectionCheckCode {
    CONNECTED(ServerStatus.ACTIVE, "SSH host key verified and authentication succeeded."),
    UNREACHABLE(ServerStatus.OFFLINE, "The SSH server could not be reached."),
    TIMED_OUT(ServerStatus.OFFLINE, "The SSH connection check timed out."),
    HOST_KEY_MISMATCH(ServerStatus.ERROR, "The SSH host key did not match the saved fingerprint."),
    AUTHENTICATION_FAILED(ServerStatus.ERROR, "The SSH server rejected the configured credential."),
    CREDENTIAL_UNAVAILABLE(ServerStatus.ERROR, "The configured SSH key could not be loaded."),
    TARGET_NOT_AUTHORIZED(ServerStatus.ERROR, "No configured SSH credential authorizes this server."),
    SSH_NOT_CONFIGURED(ServerStatus.ERROR, "SSH connection checks have no configured targets."),
    CHECK_FAILED(ServerStatus.ERROR, "The SSH connection check could not be completed.");

    private final ServerStatus status;
    private final String message;

    ConnectionCheckCode(ServerStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public ServerStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
