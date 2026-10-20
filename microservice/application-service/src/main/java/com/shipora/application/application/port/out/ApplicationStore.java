package com.shipora.application.application.port.out;

import com.shipora.application.domain.ManagedApplication;
import com.shipora.application.application.port.in.ApplicationUseCases.*;
import java.time.Instant;
import java.util.*;

public interface ApplicationStore {
    ManagedApplication create(ManagedApplication app);
    Optional<ManagedApplication> find(UUID org, UUID id);
    List<ManagedApplication> list(UUID org, UUID projectId, int offset, int limit);
    List<ManagedApplication> autoDeployApplications(UUID org,UUID repositoryId,String branch,int offset,int limit);
    ManagedApplication update(ManagedApplication app, long expectedVersion);
    void delete(UUID org, UUID id, long version, Instant now);
    EnvironmentResult environment(UUID org, UUID id);
    EnvironmentResult replaceEnvironment(UUID org, UUID id, long version, Map<String,String> variables, Instant now);
    SecretKeysResult secretKeys(UUID org, UUID id);
    SecretKeysResult putSecret(UUID org, UUID id, long version, String key, SecretCipher.Encrypted encrypted, Instant now);
    SecretKeysResult deleteSecret(UUID org, UUID id, long version, String key, Instant now);
}
