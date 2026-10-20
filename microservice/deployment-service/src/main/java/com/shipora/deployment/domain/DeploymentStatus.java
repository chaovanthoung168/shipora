package com.shipora.deployment.domain;

public enum DeploymentStatus {
    QUEUED, PREPARING, BUILDING, PUSHING, DEPLOYING, VERIFYING, SUCCEEDED, FAILED, CANCELLED;
    public boolean terminal(){return this==SUCCEEDED || this==FAILED || this==CANCELLED;}
}
