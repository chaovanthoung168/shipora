package com.shipora.deployment.domain;

import lombok.Getter;

@Getter
public final class DeploymentException extends RuntimeException {
    public enum Kind { INVALID, NOT_FOUND, FORBIDDEN, CONFLICT, UNAVAILABLE }
    private final Kind kind;
    public DeploymentException(Kind kind,String message){super(message);this.kind=kind;}
}
