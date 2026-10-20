package com.shipora.application.application.port.in;

import com.shipora.application.domain.KubernetesTarget;
import java.time.Instant;
import java.util.*;

public interface KubernetesTargetUseCases {
    Result create(UUID organizationId, Create input);
    Result get(UUID organizationId, UUID id);
    List<Result> list(UUID organizationId, int page, int size);
    Result update(UUID organizationId, UUID id, Update input);
    Result check(UUID organizationId, UUID id, long version);
    void delete(UUID organizationId, UUID id, long version);
    record Create(String name, String apiServer, String namespace, String token, String certificateAuthorityData) {
        @Override public String toString() { return "CreateKubernetesTarget[redacted]"; }
    }
    record Update(long version, String name, String token, String certificateAuthorityData) {
        @Override public String toString() { return "UpdateKubernetesTarget[redacted]"; }
    }
    record Result(UUID id, UUID organizationId, String name, String apiServer, String namespace,
                  KubernetesTarget.Status status, String checkCode, long version, Instant createdAt, Instant updatedAt, Instant checkedAt) {
        public static Result from(KubernetesTarget t) {
            return new Result(t.getId(), t.getOrganizationId(), t.getName(), t.getApiServer(), t.getNamespace(),
                    t.getStatus(), t.getCheckCode(), t.getVersion(), t.getCreatedAt(), t.getUpdatedAt(), t.getCheckedAt());
        }
    }
}
