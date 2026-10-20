package com.shipora.git.adapter.out.persistence;

import com.shipora.git.application.port.out.*;
import com.shipora.git.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.shipora.git.domain.GitException.Kind.CONFLICT;

@Repository
public class JdbcGitStore implements GitStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcGitStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override public void saveState(ConnectionState state) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM git.connection_states WHERE expires_at <= ?", Timestamp.from(state.expiresAt().minusSeconds(600)));
            jdbc.update("INSERT INTO git.connection_states (state_hash, organization_id, installation_external_id, code_verifier, expires_at) VALUES (?, ?, ?, ?, ?)", state.hash(), state.organizationId(),
                    state.installationExternalId(), state.codeVerifier(), Timestamp.from(state.expiresAt()));
        });
    }
    @Override public Optional<ConnectionState> consumeState(String hash, Instant now) {
        return transaction.execute(status -> jdbc.query("""
                DELETE FROM git.connection_states WHERE state_hash = ? AND expires_at > ?
                RETURNING *
                """, (rs, row) -> new ConnectionState(rs.getString("state_hash"), uuid(rs, "organization_id"),
                rs.getString("installation_external_id"), rs.getString("code_verifier"), instant(rs, "expires_at")),
                hash, Timestamp.from(now)).stream().findFirst());
    }
    @Override public GitInstallation connect(UUID org, GitProvider.VerifiedInstallation verified, Instant now) {
        return transaction.execute(status -> {
            var result = jdbc.query("""
                    INSERT INTO git.git_installations
                        (id, organization_id, provider, installation_external_id, account_name, created_at)
                    VALUES (?, ?, 'GITHUB', ?, ?, ?)
                    ON CONFLICT (provider, installation_external_id) DO UPDATE
                        SET account_name = EXCLUDED.account_name, active = true,
                            revision = git.git_installations.revision + 1
                        WHERE git.git_installations.organization_id = EXCLUDED.organization_id
                    RETURNING *
                    """, this::installationRow, UUID.randomUUID(), org, verified.externalId(), verified.accountName(), Timestamp.from(now));
            if (result.isEmpty()) throw new GitException(CONFLICT, "This installation is already linked to another organization.");
            return result.getFirst();
        });
    }
    @Override public Optional<GitInstallation> installation(UUID org, UUID id) {
        return jdbc.query("SELECT * FROM git.git_installations WHERE organization_id = ? AND id = ?",
                this::installationRow, org, id).stream().findFirst();
    }
    @Override public List<GitInstallation> installations(UUID org, int offset, int limit) {
        return jdbc.query("SELECT * FROM git.git_installations WHERE organization_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                this::installationRow, org, limit, offset);
    }
    @Override public List<GitRepository> repositories(UUID org, UUID id, int offset, int limit) {
        return jdbc.query("""
                SELECT r.*, (r.available AND i.active) AS effective_available FROM git.repositories r
                JOIN git.git_installations i ON i.id = r.installation_id
                WHERE i.organization_id = ? AND i.id = ? ORDER BY r.full_name, r.id LIMIT ? OFFSET ?
                """, this::repositoryRow, org, id, limit, offset);
    }
    @Override public List<GitRepository> searchRepositories(UUID org, UUID id, String query, int offset, int limit) {
        return jdbc.query("""
                SELECT r.*, (r.available AND i.active) AS effective_available FROM git.repositories r
                JOIN git.git_installations i ON i.id = r.installation_id
                WHERE i.organization_id = ? AND i.id = ? AND strpos(lower(r.full_name), lower(?)) > 0
                ORDER BY r.full_name, r.id LIMIT ? OFFSET ?
                """, this::repositoryRow, org, id, query, limit, offset);
    }
    @Override public Optional<GitRepository> repository(UUID org, UUID id) {
        return jdbc.query("""
                SELECT r.*, (r.available AND i.active) AS effective_available FROM git.repositories r
                JOIN git.git_installations i ON i.id = r.installation_id
                WHERE i.organization_id = ? AND r.id = ?
                """, this::repositoryRow, org, id).stream().findFirst();
    }
    @Override public void replaceRepositories(GitInstallation expected, List<GitProvider.RepositorySnapshot> snapshot, Instant now) {
        transaction.executeWithoutResult(status -> {
            var current = jdbc.query("SELECT * FROM git.git_installations WHERE id = ? AND organization_id = ? FOR UPDATE",
                    this::installationRow, expected.id(), expected.organizationId());
            if (current.isEmpty() || !current.getFirst().active() || current.getFirst().revision() != expected.revision()) {
                throw new GitException(CONFLICT, "Installation changed during synchronization. Retry or reconnect.");
            }
            jdbc.update("UPDATE git.repositories SET available = false WHERE installation_id = ?", expected.id());
            for (var repo : snapshot) {
                jdbc.update("""
                        INSERT INTO git.repositories (id, installation_id, external_repository_id, full_name,
                            clone_url, default_branch, is_private, available, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, true, ?)
                        ON CONFLICT (installation_id, external_repository_id) DO UPDATE
                            SET full_name = EXCLUDED.full_name, clone_url = EXCLUDED.clone_url,
                                default_branch = EXCLUDED.default_branch, is_private = EXCLUDED.is_private, available = true
                        """, UUID.randomUUID(), expected.id(), repo.externalId(), repo.fullName(), repo.cloneUrl(),
                        repo.defaultBranch(), repo.privateRepository(), Timestamp.from(now));
            }
            jdbc.update("UPDATE git.git_installations SET revision = revision + 1 WHERE id = ?", expected.id());
        });
    }
    @Override public boolean recordWebhook(WebhookDelivery delivery, Instant now) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var installations = jdbc.query("SELECT * FROM git.git_installations WHERE installation_external_id = ? FOR UPDATE",
                    this::installationRow, delivery.installationExternalId());
            UUID installationId = installations.isEmpty() ? null : installations.getFirst().id();
            List<UUID> repositories = installationId == null ? List.of() : jdbc.query("""
                    SELECT id FROM git.repositories WHERE installation_id = ? AND external_repository_id = ?
                    """, (rs, row) -> uuid(rs, "id"), installationId, delivery.repositoryExternalId());
            int inserted = jdbc.update("""
                    INSERT INTO git.webhook_deliveries
                        (id, repository_id, delivery_id, event_type, commit_sha, status, received_at, processed_at)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSED', ?, ?) ON CONFLICT (delivery_id) DO NOTHING
                    """, UUID.randomUUID(), repositories.isEmpty() ? null : repositories.getFirst(), delivery.deliveryId(),
                    delivery.eventType(), delivery.commitSha(), Timestamp.from(now), Timestamp.from(now));
            if (inserted == 0) return false;
            if("push".equals(delivery.eventType()) && delivery.branch()!=null && delivery.commitSha()!=null && !delivery.commitSha().matches("0+") && !repositories.isEmpty()){
                jdbc.update("""
                    INSERT INTO git.deployment_push_outbox(id,organization_id,repository_id,commit_sha,branch,created_at)
                    SELECT ?,i.organization_id,r.id,?,?,? FROM git.repositories r JOIN git.git_installations i ON i.id=r.installation_id
                    WHERE r.id=? AND r.available AND i.active
                    ON CONFLICT (id) DO NOTHING
                    """,UUID.fromString(delivery.deliveryId()),delivery.commitSha(),delivery.branch(),Timestamp.from(now),repositories.getFirst());
            }
            if (installationId != null && (delivery.invalidateInstallation() || delivery.invalidateRepositories())) {
                jdbc.update("UPDATE git.repositories SET available = false WHERE installation_id = ?", installationId);
                jdbc.update("UPDATE git.git_installations SET revision = revision + 1, active = active AND ? WHERE id = ?",
                        !delivery.invalidateInstallation(), installationId);
            }
            return true;
        }));
    }
    private GitInstallation installationRow(ResultSet rs, int row) throws SQLException {
        return new GitInstallation(uuid(rs, "id"), uuid(rs, "organization_id"), rs.getString("installation_external_id"),
                rs.getString("account_name"), rs.getBoolean("active"), rs.getLong("revision"), instant(rs, "created_at"));
    }
    private GitRepository repositoryRow(ResultSet rs, int row) throws SQLException {
        return new GitRepository(uuid(rs, "id"), uuid(rs, "installation_id"), rs.getString("external_repository_id"),
                rs.getString("full_name"), rs.getString("clone_url"), rs.getString("default_branch"),
                rs.getBoolean("is_private"), rs.getBoolean("effective_available"), instant(rs, "created_at"));
    }
    private static UUID uuid(ResultSet rs, String name) throws SQLException { return rs.getObject(name, UUID.class); }
    private static Instant instant(ResultSet rs, String name) throws SQLException { return rs.getTimestamp(name).toInstant(); }
}
