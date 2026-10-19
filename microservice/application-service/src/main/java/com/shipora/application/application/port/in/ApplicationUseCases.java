package com.shipora.application.application.port.in;

import com.shipora.application.application.dto.*;
import java.util.*;

public interface ApplicationUseCases {
    ApplicationResult create(UUID organizationId, CreateApplication input);
    ApplicationResult get(UUID organizationId, UUID id);
    List<ApplicationResult> list(UUID organizationId, UUID projectId, int page, int size);
    ApplicationResult update(UUID organizationId, UUID id, UpdateApplication input);
    void delete(UUID organizationId, UUID id, long version);
    EnvironmentResult environment(UUID organizationId, UUID id);
    EnvironmentResult replaceEnvironment(UUID organizationId, UUID id, long version, Map<String,String> variables);
    SecretKeysResult secretKeys(UUID organizationId, UUID id);
    SecretKeysResult putSecret(UUID organizationId, UUID id, long version, String key, String value);
    SecretKeysResult deleteSecret(UUID organizationId, UUID id, long version, String key);
    record EnvironmentResult(long version, Map<String,String> variables) {
        public EnvironmentResult { variables = Map.copyOf(variables); }
        @Override public String toString() { return "EnvironmentResult[redacted]"; }
    }
    record SecretKeysResult(long version, List<String> keys) { public SecretKeysResult { keys = List.copyOf(keys); } }
}
