package com.shipora.deployment.application.dto;

import com.shipora.deployment.domain.*;
import java.util.UUID;

public record ChangeDeploymentStatus(UUID leaseToken,DeploymentStatus expectedStatus,DeploymentStatus status,String imageReference,FailureCode failureCode,String runtimeEndpoint) {
    public ChangeDeploymentStatus(UUID token,DeploymentStatus expected,DeploymentStatus status,String image,FailureCode failure){this(token,expected,status,image,failure,null);}
    @Override public String toString(){return "ChangeDeploymentStatus[redacted]";}
}
