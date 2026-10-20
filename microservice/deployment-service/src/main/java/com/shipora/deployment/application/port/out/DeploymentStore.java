package com.shipora.deployment.application.port.out;

import com.shipora.deployment.application.dto.*;
import com.shipora.deployment.domain.*;
import java.util.*;

public interface DeploymentStore {
    Optional<Deployment> find(UUID org,UUID id);
    Optional<Deployment> findByKey(UUID org,String requestKey);
    Deployment create(Deployment deployment);
    List<Deployment> list(UUID org,UUID applicationId,int offset,int limit);
    Deployment cancel(UUID org,UUID id,long version);
    List<DeploymentEvent> events(UUID org,UUID id,long afterId,int limit);
    QueueStatus queueStatus(UUID org,UUID id);
    void appendEvents(UUID org,String worker,UUID job,UUID token,List<WorkerEvent> events);
    List<ReleaseResult> releases(UUID org,UUID applicationId,int offset,int limit);
    Optional<JobClaim> claim(UUID org,String worker);
    LeaseResult heartbeat(UUID org,String worker,UUID jobId,UUID leaseToken);
    JobClaim authorize(UUID org,String worker,UUID jobId,UUID leaseToken);
    Deployment progress(UUID org,String worker,UUID jobId,ChangeDeploymentStatus input);
}
