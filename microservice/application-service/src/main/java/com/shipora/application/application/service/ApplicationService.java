package com.shipora.application.application.service;

import com.shipora.application.application.dto.*;
import com.shipora.application.application.port.in.ApplicationUseCases;
import com.shipora.application.application.port.out.*;
import com.shipora.application.domain.*;
import lombok.RequiredArgsConstructor;
import java.time.Clock;
import java.util.*;
import static com.shipora.application.domain.ApplicationRules.*;

@RequiredArgsConstructor
public final class ApplicationService implements ApplicationUseCases {
    private final ApplicationStore store;
    private final ResourceCatalog catalog;
    private final SecretCipher cipher;
    private final Clock clock;
    private final KubernetesTargetStore targets;

    @Override public ApplicationResult create(UUID org, CreateApplication in) {
        id(org); id(in.projectId()); id(in.repositoryId());
        var now=clock.instant();
        // Validate input before network I/O. Repository default branch is resolved after access verification.
        var app=ManagedApplication.builder().id(UUID.randomUUID()).organizationId(org).projectId(in.projectId())
                .repositoryId(in.repositoryId()).serverId(in.serverId()).runtimeType(or(in.runtimeType(),RuntimeType.DOCKER))
                .kubernetesTargetId(in.kubernetesTargetId()).name(text(in.name(),150,"name")).slug(slug(in.slug()))
                .branch(in.branch()==null?"main":branch(in.branch())).dockerfilePath(path(or(in.dockerfilePath(),"Dockerfile"),false))
                .buildContext(path(or(in.buildContext(),"."),true)).containerPort(port(in.containerPort())).healthPath(health(or(in.healthPath(),"/")))
                .autoDeploy(Boolean.TRUE.equals(in.autoDeploy())).version(0).createdAt(now).updatedAt(now).build();
        validateTarget(app);
        catalog.requireProject(org,app.getProjectId());
        String defaultBranch=catalog.requireRepository(org,app.getRepositoryId());
        requireTarget(app);
        if(in.branch()==null) app=app.toBuilder().branch(branch(defaultBranch)).build();
        return ApplicationResult.from(store.create(app));
    }
    @Override public ApplicationResult get(UUID org,UUID id){return ApplicationResult.from(require(org,id));}
    @Override public List<ApplicationResult> list(UUID org,UUID projectId,int page,int size) {
        return store.list(org,projectId,offset(page,size),size).stream().map(ApplicationResult::from).toList();
    }
    @Override public ApplicationResult update(UUID org,UUID id,UpdateApplication in) {
        version(in.version()); var old=require(org,id);
        if(old.getVersion()!=in.version()) throw new ApplicationException(ApplicationException.Kind.CONFLICT,"Application version changed. Reload and retry.");
        RuntimeType runtime=or(in.runtimeType(),old.getRuntimeType());
        boolean switching=runtime!=old.getRuntimeType();
        // Switching requires a new destination; an omitted runtime preserves the existing destination.
        UUID server=runtime==RuntimeType.DOCKER?or(in.serverId(),switching?null:old.getServerId()):in.serverId();
        UUID target=runtime==RuntimeType.KUBERNETES?or(in.kubernetesTargetId(),switching?null:old.getKubernetesTargetId()):in.kubernetesTargetId();
        var app=old.toBuilder().repositoryId(or(in.repositoryId(),old.getRepositoryId())).serverId(server).runtimeType(runtime).kubernetesTargetId(target)
                .name(text(or(in.name(),old.getName()),150,"name")).slug(slug(or(in.slug(),old.getSlug())))
                .branch(branch(or(in.branch(),old.getBranch()))).dockerfilePath(path(or(in.dockerfilePath(),old.getDockerfilePath()),false))
                .buildContext(path(or(in.buildContext(),old.getBuildContext()),true)).containerPort(port(or(in.containerPort(),old.getContainerPort())))
                .healthPath(health(or(in.healthPath(),old.getHealthPath()))).autoDeploy(or(in.autoDeploy(),old.isAutoDeploy()))
                .version(in.version()+1).updatedAt(clock.instant()).build();
        validateTarget(app);
        catalog.requireProject(org,app.getProjectId()); catalog.requireRepository(org,app.getRepositoryId()); requireTarget(app);
        return ApplicationResult.from(store.update(app,in.version()));
    }
    @Override public void delete(UUID org,UUID id,long expected){version(expected);store.delete(org,id,expected,clock.instant());}
    @Override public EnvironmentResult environment(UUID org,UUID id){return store.environment(org,id);}
    @Override public EnvironmentResult replaceEnvironment(UUID org,UUID id,long expected,Map<String,String> variables){
        version(expected); return store.replaceEnvironment(org,id,expected,ApplicationRules.environment(variables),clock.instant());
    }
    @Override public SecretKeysResult secretKeys(UUID org,UUID id){return store.secretKeys(org,id);}
    @Override public SecretKeysResult putSecret(UUID org,UUID id,long expected,String key,String value){
        version(expected); key(key); value(value);
        var app=require(org,id);
        if(app.getVersion()!=expected) throw new ApplicationException(ApplicationException.Kind.CONFLICT,"Application version changed. Reload and retry.");
        var encrypted=cipher.encrypt(org,id,key,value);
        return store.putSecret(org,id,expected,key,encrypted,clock.instant());
    }
    @Override public SecretKeysResult deleteSecret(UUID org,UUID id,long expected,String key){
        version(expected);key(key);return store.deleteSecret(org,id,expected,key,clock.instant());
    }
    private ManagedApplication require(UUID org,UUID id){return store.find(org,id).orElseThrow(()->new ApplicationException(ApplicationException.Kind.NOT_FOUND,"Application not found."));}
    private static void validateTarget(ManagedApplication app) {
        boolean valid=app.getRuntimeType()==RuntimeType.DOCKER?app.getServerId()!=null && app.getKubernetesTargetId()==null
                :app.getKubernetesTargetId()!=null && app.getServerId()==null;
        if(!valid)throw new ApplicationException(ApplicationException.Kind.INVALID,"DOCKER requires only serverId; KUBERNETES requires only kubernetesTargetId.");
    }
    private void requireTarget(ManagedApplication app) {
        if(app.getRuntimeType()==RuntimeType.DOCKER) { catalog.requireActiveServer(app.getOrganizationId(),app.getServerId());return; }
        var target=targets.find(app.getOrganizationId(),app.getKubernetesTargetId())
                .orElseThrow(()->new ApplicationException(ApplicationException.Kind.NOT_FOUND,"Kubernetes target not found."));
        if(target.getStatus()!=KubernetesTarget.Status.ACTIVE)throw new ApplicationException(ApplicationException.Kind.CONFLICT,"Run a successful Kubernetes target connection check first.");
    }
    private static <T> T or(T value,T fallback){return value==null?fallback:value;}
}
