package com.shipora.application.application.service;

import com.shipora.application.application.port.in.KubernetesTargetUseCases;
import com.shipora.application.application.port.out.*;
import com.shipora.application.domain.*;
import lombok.RequiredArgsConstructor;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import static com.shipora.application.domain.ApplicationRules.*;
import static com.shipora.application.domain.ApplicationException.Kind.*;

@RequiredArgsConstructor
public final class KubernetesTargetService implements KubernetesTargetUseCases {
    private final KubernetesTargetStore store;
    private final KubernetesCredentialVault vault;
    private final KubernetesProbe probe;
    private final Clock clock;
    private final Set<UUID> checking = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity = new Semaphore(4);

    @Override public Result create(UUID org, Create in) {
        id(org);
        String name = text(in.name(), 150, "name");
        var access = new KubernetesAccess(in.apiServer(), in.namespace(), in.token(), in.certificateAuthorityData());
        UUID targetId = UUID.randomUUID(); var now = clock.instant();
        return Result.from(store.create(KubernetesTarget.builder().id(targetId).organizationId(org).name(name)
                .apiServer(access.apiServer()).namespace(access.namespace()).credentials(vault.encrypt(org,targetId,access))
                .status(KubernetesTarget.Status.UNVERIFIED).version(0).createdAt(now).updatedAt(now).build()));
    }
    @Override public Result get(UUID org, UUID id) { return Result.from(require(org,id)); }
    @Override public List<Result> list(UUID org, int page, int size) {
        return store.list(org,offset(page,size),size).stream().map(Result::from).toList();
    }
    @Override public Result update(UUID org, UUID id, Update in) {
        version(in.version()); var old = require(org,id); sameVersion(old,in.version());
        String name = text(in.name()==null?old.getName():in.name(),150,"name");
        var builder = old.toBuilder().name(name).version(in.version()+1).updatedAt(clock.instant());
        if (in.token()!=null || in.certificateAuthorityData()!=null) {
            var previous = vault.decrypt(org,id,old.getCredentials());
            var access = new KubernetesAccess(old.getApiServer(),old.getNamespace(),in.token()==null?previous.token():in.token(),
                    in.certificateAuthorityData()==null?previous.certificateAuthorityData():in.certificateAuthorityData());
            builder.credentials(vault.encrypt(org,id,access)).status(KubernetesTarget.Status.UNVERIFIED).checkCode(null).checkedAt(null);
        }
        return Result.from(store.update(builder.build(),in.version()));
    }
    @Override public Result check(UUID org, UUID id, long expected) {
        version(expected); var old = require(org,id); sameVersion(old,expected);
        if (!checking.add(id)) throw new ApplicationException(CONFLICT,"A connection check is already running for this target.");
        boolean acquired = capacity.tryAcquire();
        try {
            if (!acquired) throw new ApplicationException(UNAVAILABLE,"Connection check capacity is busy. Retry later.");
            var result = probe.check(vault.decrypt(org,id,old.getCredentials()));
            // The CAS discards a result if credentials changed while the request was in flight.
            var now = clock.instant();
            return Result.from(store.update(old.toBuilder().status(result.allowed()?KubernetesTarget.Status.ACTIVE:KubernetesTarget.Status.ERROR)
                    .checkCode(result.code().name()).checkedAt(now).updatedAt(now).version(expected+1).build(),expected));
        } finally { if(acquired) capacity.release(); checking.remove(id); }
    }
    @Override public void delete(UUID org, UUID id, long expected) { version(expected); store.delete(org,id,expected,clock.instant()); }
    private KubernetesTarget require(UUID org, UUID id) {
        return store.find(org,id).orElseThrow(()->new ApplicationException(NOT_FOUND,"Kubernetes target not found."));
    }
    private static void sameVersion(KubernetesTarget target, long expected) {
        if(target.getVersion()!=expected) throw new ApplicationException(CONFLICT,"Kubernetes target version changed. Reload and retry.");
    }
}
