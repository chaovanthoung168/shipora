package com.shipora.git.adapter.out.persistence;

import com.shipora.git.application.port.in.GitConnectionUseCases.AccountChoice;
import com.shipora.git.application.port.out.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcConnectionStore implements ConnectionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcConnectionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
    }
    @Override public void savePending(Pending pending) {
        transaction.executeWithoutResult(status -> {
            lockConnection(pending.tokenHash());
            jdbc.update("DELETE FROM git.connection_states WHERE expires_at <= ?", Timestamp.from(pending.expiresAt().minusSeconds(600)));
            jdbc.update("DELETE FROM git.connection_states WHERE connection_token_hash = ?", pending.tokenHash());
            jdbc.update("""
                    INSERT INTO git.connection_states (state_hash, organization_id, installation_external_id, code_verifier, expires_at, connection_token_hash)
                    VALUES (?, ?, NULL, ?, ?, ?)
                    """, pending.stateHash(), pending.organizationId(), pending.verifier(), Timestamp.from(pending.expiresAt()), pending.tokenHash());
            jdbc.update("DELETE FROM git.connection_choices WHERE connection_token_hash = ?", pending.tokenHash());
        });
    }
    @Override public Optional<Pending> consumePending(String hash, UUID org, Instant now) {
        return transaction.execute(status -> jdbc.query("""
                DELETE FROM git.connection_states WHERE state_hash = ? AND organization_id = ?
                    AND connection_token_hash IS NOT NULL AND installation_external_id IS NULL AND expires_at > ? RETURNING *
                """, (rs, row) -> new Pending(rs.getString("state_hash"), rs.getObject("organization_id", UUID.class),
                rs.getString("connection_token_hash"), rs.getString("code_verifier"), rs.getTimestamp("expires_at").toInstant()),
                hash, org, Timestamp.from(now)).stream().findFirst());
    }
    @Override public void saveChoices(UUID org, String tokenHash, List<GitProvider.VerifiedInstallation> accounts, Instant expires) {
        transaction.executeWithoutResult(status -> {
            lockConnection(tokenHash);
            jdbc.update("DELETE FROM git.connection_choices WHERE expires_at <= ? OR connection_token_hash = ?",
                    Timestamp.from(expires.minusSeconds(600)), tokenHash);
            for (var account : accounts) {
                jdbc.update("INSERT INTO git.connection_choices VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), org,
                        tokenHash, account.externalId(), account.accountName(), Timestamp.from(expires));
            }
        });
    }
    @Override public List<AccountChoice> choices(UUID org, String tokenHash, Instant now) {
        return jdbc.query("""
                SELECT id, account_name FROM git.connection_choices
                WHERE organization_id = ? AND connection_token_hash = ? AND expires_at > ? ORDER BY account_name, id
                """, (rs, row) -> new AccountChoice(rs.getObject("id", UUID.class), rs.getString("account_name")), org, tokenHash, Timestamp.from(now));
    }
    @Override public Optional<GitProvider.VerifiedInstallation> consumeChoice(UUID org, String tokenHash, UUID id, Instant now) {
        return transaction.execute(status -> {
            lockConnection(tokenHash);
            var selected = jdbc.query("""
                    DELETE FROM git.connection_choices WHERE organization_id = ? AND connection_token_hash = ?
                        AND id = ? AND expires_at > ? RETURNING installation_external_id, account_name
                    """, (rs, row) -> new GitProvider.VerifiedInstallation(rs.getString("installation_external_id"), rs.getString("account_name")),
                    org, tokenHash, id, Timestamp.from(now));
            if (!selected.isEmpty()) jdbc.update("DELETE FROM git.connection_choices WHERE organization_id = ? AND connection_token_hash = ?", org, tokenHash);
            return selected.stream().findFirst();
        });
    }
    private void lockConnection(String tokenHash) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {}, tokenHash);
    }
}
