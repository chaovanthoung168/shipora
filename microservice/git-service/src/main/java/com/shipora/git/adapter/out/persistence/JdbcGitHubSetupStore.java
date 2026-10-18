package com.shipora.git.adapter.out.persistence;

import com.shipora.git.application.dto.GitHubAppCredentials;
import com.shipora.git.application.port.out.GitHubSetupStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcGitHubSetupStore implements GitHubSetupStore {
    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();
    public JdbcGitHubSetupStore(JdbcTemplate jdbc, CredentialCipher cipher, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.cipher = cipher; this.transaction = new TransactionTemplate(manager);
    }
    @Override public void prepareCredentialStorage() { cipher.encrypt("Shipora credential storage readiness"); }
    @Override public Optional<GitHubAppCredentials> credentials() {
        return jdbc.query("SELECT encrypted_credentials FROM git.github_app_configuration WHERE singleton = TRUE",
                (rs, row) -> json.readValue(cipher.decrypt(rs.getString(1)), GitHubAppCredentials.class)).stream().findFirst();
    }
    @Override public boolean saveCredentialsIfAbsent(GitHubAppCredentials credentials, Instant now) {
        String encrypted = cipher.encrypt(json.writeValueAsString(credentials));
        return transaction.execute(status -> {
            boolean saved = jdbc.update("""
                    INSERT INTO git.github_app_configuration (singleton, encrypted_credentials, created_at)
                    VALUES (TRUE, ?, ?) ON CONFLICT (singleton) DO NOTHING
                    """, encrypted, Timestamp.from(now)) == 1;
            if (saved) jdbc.update("DELETE FROM git.github_setup_states");
            return saved;
        });
    }
    @Override public void savePending(Pending pending, Instant now) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM git.github_setup_states WHERE expires_at <= ? OR (organization_id = ? AND actor = ?)",
                    Timestamp.from(now), pending.organizationId(), pending.actor());
            jdbc.update("""
                    INSERT INTO git.github_setup_states (state_hash, organization_id, actor, callback_url, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, pending.stateHash(), pending.organizationId(), pending.actor(), pending.callbackUrl().toString(), Timestamp.from(pending.expiresAt()));
        });
    }
    @Override public Optional<Pending> consumePending(String hash, UUID org, String actor, Instant now) {
        return jdbc.query("""
                DELETE FROM git.github_setup_states WHERE state_hash = ? AND organization_id = ? AND actor = ? AND expires_at > ?
                RETURNING *
                """, (rs, row) -> new Pending(rs.getString("state_hash"), rs.getObject("organization_id", UUID.class),
                rs.getString("actor"), URI.create(rs.getString("callback_url")), rs.getTimestamp("expires_at").toInstant()),
                hash, org, actor, Timestamp.from(now)).stream().findFirst();
    }
}
