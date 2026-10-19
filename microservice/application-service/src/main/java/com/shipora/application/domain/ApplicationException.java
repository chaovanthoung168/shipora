package com.shipora.application.domain;

import lombok.Getter;

@Getter
public final class ApplicationException extends RuntimeException {
    private final Kind kind;
    public ApplicationException(Kind kind, String message) { super(message); this.kind = kind; }
    public enum Kind { INVALID, NOT_FOUND, FORBIDDEN, CONFLICT, DEPENDENCY, UNAVAILABLE }
}
