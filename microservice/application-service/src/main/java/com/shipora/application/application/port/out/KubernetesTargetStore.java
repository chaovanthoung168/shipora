package com.shipora.application.application.port.out;

import com.shipora.application.domain.KubernetesTarget;
import java.time.Instant;
import java.util.*;

public interface KubernetesTargetStore {
    KubernetesTarget create(KubernetesTarget target);
    Optional<KubernetesTarget> find(UUID organizationId, UUID id);
    List<KubernetesTarget> list(UUID organizationId, int offset, int limit);
    KubernetesTarget update(KubernetesTarget target, long expectedVersion);
    void delete(UUID organizationId, UUID id, long expectedVersion, Instant now);
}
