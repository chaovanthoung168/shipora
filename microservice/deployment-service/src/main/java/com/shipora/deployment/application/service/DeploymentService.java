package com.shipora.deployment.application.service;

import com.shipora.deployment.application.dto.*;
import com.shipora.deployment.application.port.in.DeploymentUseCases;
import com.shipora.deployment.application.port.out.*;
import com.shipora.deployment.domain.*;
import lombok.RequiredArgsConstructor;
import java.util.*;
import static com.shipora.deployment.domain.DeploymentRules.*;

@RequiredArgsConstructor
public final class DeploymentService implements DeploymentUseCases {
    private final DeploymentStore store;
    private final DeploymentCatalog catalog;
    @Override public DeploymentResult create(UUID org,String actor,String requestKey,CreateDeployment input){
        return create(org,actor,requestKey,id(input.applicationId()),commit(input.commitSha()),null);
    }
    @Override public DeploymentResult retry(UUID org,String actor,UUID failedId,String requestKey){
        var failed=require(org,id(failedId));
        if(failed.getStatus()!=DeploymentStatus.FAILED)throw conflict("Only FAILED deployments can be retried.");
        return create(org,actor,requestKey,failed.getApplicationId(),failed.getCommitSha(),failedId);
    }
    private DeploymentResult create(UUID org,String actor,String requestKey,UUID app,String commit,UUID retryOf){
        id(org);actor(actor);key(requestKey);
        if(requestKey.startsWith("push:"))throw invalid("The push: idempotency-key prefix is reserved for verified push events.");
        var existing=store.findByKey(org,requestKey);
        if(existing.isPresent()){sameRequest(existing.get(),app,commit,retryOf);return DeploymentResult.from(existing.get());}
        var snapshot=catalog.requireReadyApplication(org,app);
        var deployment=Deployment.builder().id(UUID.randomUUID()).organizationId(org).applicationId(app).configuration(snapshot)
                .commitSha(commit).branch(snapshot.branch()).triggerType(retryOf==null?"MANUAL":"RETRY").requestedBy(actor).requestKey(requestKey)
                .retryOf(retryOf).status(DeploymentStatus.QUEUED).version(0).build();
        var saved=store.create(deployment);sameRequest(saved,app,commit,retryOf);return DeploymentResult.from(saved);
    }
    public static void sameRequest(Deployment saved,UUID app,String commit,UUID retryOf){
        if(!saved.getApplicationId().equals(app)||!saved.getCommitSha().equals(commit)||!Objects.equals(saved.getRetryOf(),retryOf))
            throw conflict("Idempotency-Key was already used for a different deployment request.");
    }
    @Override public DeploymentResult get(UUID org,UUID id){return DeploymentResult.from(require(org,id));}
    @Override public List<DeploymentResult> list(UUID org,UUID app,int page,int size){return store.list(org,app,offset(page,size),size).stream().map(DeploymentResult::from).toList();}
    @Override public DeploymentResult cancel(UUID org,UUID id,long version){version(version);return DeploymentResult.from(store.cancel(org,id,version));}
    @Override public List<DeploymentEvent> events(UUID org,UUID id,long after,int limit){eventPage(after,limit);return store.events(org,id,after,limit);}
    @Override public QueueStatus queueStatus(UUID org,UUID id){id(org);id(id);return store.queueStatus(org,id);}
    @Override public void appendEvents(UUID org,String worker,UUID job,UUID token,List<WorkerEvent> events){
        id(org);id(job);id(token);actor(worker);
        if(events==null||events.isEmpty()||events.size()>50)throw invalid("Provide between 1 and 50 process events.");
        for(var event:events){
            if(event==null||event.level()==null||!Set.of("INFO","WARN","ERROR").contains(event.level())
                    ||event.message()==null||event.message().isBlank()||event.message().length()>2048
                    ||event.message().chars().anyMatch(c->Character.isISOControl(c)&&c!='\t'))
                throw invalid("Process events require a valid level and a single message of at most 2048 characters.");
        }
        store.appendEvents(org,worker,job,token,List.copyOf(events));
    }
    @Override public List<ReleaseResult> releases(UUID org,UUID app,int page,int size){id(app);return store.releases(org,app,offset(page,size),size);}
    @Override public Optional<JobClaim> claim(UUID org,String worker){id(org);actor(worker);return store.claim(org,worker);}
    @Override public LeaseResult heartbeat(UUID org,String worker,UUID job,UUID token){id(job);id(token);return store.heartbeat(org,actor(worker),job,token);}
    @Override public JobClaim authorize(UUID org,String worker,UUID job,UUID token){id(job);id(token);return store.authorize(org,actor(worker),job,token);}
    @Override public DeploymentResult progress(UUID org,String worker,UUID job,ChangeDeploymentStatus input){
        id(job);id(input.leaseToken());if(input.expectedStatus()==null || input.status()==null)throw invalid("expectedStatus and status are required.");
        if(input.runtimeEndpoint()!=null){
            try{var uri=java.net.URI.create(input.runtimeEndpoint());if(input.status()!=DeploymentStatus.SUCCEEDED || input.runtimeEndpoint().length()>2048
                    || !Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null)throw new IllegalArgumentException();}
            catch(IllegalArgumentException e){throw invalid("runtimeEndpoint must be a credential-free HTTP(S) URL on SUCCEEDED.");}
        }
        return DeploymentResult.from(store.progress(org,actor(worker),job,input));
    }
    private Deployment require(UUID org,UUID id){return store.find(org,id).orElseThrow(()->new DeploymentException(DeploymentException.Kind.NOT_FOUND,"Deployment not found."));}
}
