package com.shipora.application.adapter.out.persistence;

import com.shipora.application.application.port.out.SecretKeyRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcSecretKeyRegistry implements SecretKeyRegistry {
    private final JdbcTemplate jdbc;
    @Override public Optional<String> fingerprint(String version) {
        return jdbc.queryForList("SELECT key_fingerprint FROM application.secret_key_registry WHERE key_version=?",String.class,version).stream().findFirst();
    }
    @Override public void registerIfAbsent(String version,String fingerprint) {
        jdbc.update("INSERT INTO application.secret_key_registry(key_version,key_fingerprint) VALUES(?,?) ON CONFLICT(key_version) DO NOTHING",version,fingerprint);
    }
}
