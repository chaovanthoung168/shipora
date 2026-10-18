package com.shipora.git.application.port.in;

import java.time.Instant;
import java.util.*;

public interface GitHubSetupUseCases {
    Status status();
    Registration start(UUID organizationId, String actor, String name, String githubOrganization);
    Completion complete(UUID organizationId, String actor, String state, String code);
    record Status(boolean configured, boolean setupRequired, String source, String appId, String installationUrl) {}
    record Registration(String actionUrl, String method, Map<String, String> formFields, Instant expiresAt) {
        public Registration { formFields = Map.copyOf(formFields); }
        @Override public String toString() { return "GitHubRegistration[redacted]"; }
    }
    record Completion(boolean configured, String appId, String installationUrl) {}
}
