package com.shipora.application.application.port.out;

import com.shipora.application.domain.KubernetesAccess;
import com.shipora.application.domain.KubernetesTarget.Credentials;
import java.util.UUID;

public interface KubernetesCredentialVault {
    Credentials encrypt(UUID organizationId, UUID targetId, KubernetesAccess access);
    KubernetesAccess decrypt(UUID organizationId, UUID targetId, Credentials encrypted);
}
