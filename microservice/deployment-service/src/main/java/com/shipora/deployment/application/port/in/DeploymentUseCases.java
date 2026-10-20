package com.shipora.deployment.application.port.in;

import com.shipora.deployment.application.dto.*;
import java.util.*;

public interface DeploymentUseCases {
    DeploymentResult create(UUID org,String actor,String requestKey,CreateDeployment input);
    DeploymentResult retry(UUID org,String actor,UUID failedDeploymentId,String requestKey);
    DeploymentResult get(UUID org,UUID id);
    List<DeploymentResult> list(UUID org,UUID applicationId,int page,int size);
    DeploymentResult cancel(UUID org,UUID id,long version);
    List<DeploymentEvent> events(UUID org,UUID id,long after,int limit);
    QueueStatus queueStatus(UUID org,UUID id);
    void appendEvents(UUID org,String worker,UUID job,UUID token,List<WorkerEvent> events);
    List<ReleaseResult> releases(UUID org,UUID applicationId,int page,int size);
    Optional<JobClaim> claim(UUID org,String worker);
    LeaseResult heartbeat(UUID org,String worker,UUID jobId,UUID leaseToken);
    JobClaim authorize(UUID org,String worker,UUID jobId,UUID leaseToken);
    DeploymentResult progress(UUID org,String worker,UUID jobId,ChangeDeploymentStatus input);
}
