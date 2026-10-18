package com.shipora.git.domain;

import lombok.Getter;

@Getter
public final class GitException extends RuntimeException {
    private final Kind kind;

    public GitException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public enum Kind { INVALID, NOT_FOUND, FORBIDDEN, CONFLICT, NOT_CONFIGURED, PROVIDER, TOO_LARGE }
}
