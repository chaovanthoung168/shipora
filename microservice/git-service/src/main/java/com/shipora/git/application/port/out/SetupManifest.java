package com.shipora.git.application.port.out;

/** Deployment URLs and provider-specific manifest serialization remain in the adapter. */
public interface SetupManifest {
    Manifest create(String name);
    record Manifest(String json, java.net.URI authorizationCallback) {}
}
