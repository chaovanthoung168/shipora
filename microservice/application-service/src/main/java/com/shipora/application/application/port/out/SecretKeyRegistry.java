package com.shipora.application.application.port.out;

import java.util.Optional;

/** A database marker prevents silently replacing a missing key while encrypted secrets still exist. */
public interface SecretKeyRegistry {
    Optional<String> fingerprint(String version);
    void registerIfAbsent(String version,String fingerprint);
}
