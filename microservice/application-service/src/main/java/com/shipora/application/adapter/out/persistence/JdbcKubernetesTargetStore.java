package com.shipora.application.adapter.out.persistence;

import com.shipora.application.application.port.out.KubernetesTargetStore;
import com.shipora.application.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.shipora.application.domain.ApplicationException.Kind.*;

@Repository
public class JdbcKubernetesTargetStore implements KubernetesTargetStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public JdbcKubernetesTargetStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); }
    @Override public KubernetesTarget create(KubernetesTarget t) {
        jdbc.update("""
            INSERT INTO application.kubernetes_targets(id,organization_id,name,api_server,namespace,encrypted_credentials,
                encryption_key_version,status,version,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,t.getId(),t.getOrganizationId(),t.getName(),t.getApiServer(),t.getNamespace(),t.getCredentials().ciphertext(),
                t.getCredentials().keyVersion(),t.getStatus().name(),t.getVersion(),Timestamp.from(t.getCreatedAt()),Timestamp.from(t.getUpdatedAt()));
        return t;
    }
    @Override public Optional<KubernetesTarget> find(UUID org,UUID id) {
        return jdbc.query("SELECT * FROM application.kubernetes_targets WHERE organization_id=? AND id=? AND deleted_at IS NULL",this::row,org,id).stream().findFirst();
    }
    @Override public List<KubernetesTarget> list(UUID org,int offset,int limit) {
        return jdbc.query("SELECT * FROM application.kubernetes_targets WHERE organization_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                this::row,org,limit,offset);
    }
    @Override public KubernetesTarget update(KubernetesTarget t,long expected) {
        return tx.execute(status->{
            lock(t.getOrganizationId(),t.getId(),expected);
            jdbc.update("""
                UPDATE application.kubernetes_targets SET name=?,encrypted_credentials=?,encryption_key_version=?,status=?,
                    check_code=?,checked_at=?,version=version+1,updated_at=? WHERE organization_id=? AND id=?
                """,t.getName(),t.getCredentials().ciphertext(),t.getCredentials().keyVersion(),t.getStatus().name(),t.getCheckCode(),
                    timestamp(t.getCheckedAt()),Timestamp.from(t.getUpdatedAt()),t.getOrganizationId(),t.getId());
            return find(t.getOrganizationId(),t.getId()).orElseThrow(JdbcKubernetesTargetStore::missing);
        });
    }
    @Override public void delete(UUID org,UUID id,long expected,Instant now) {
        tx.executeWithoutResult(status->{
            lock(org,id,expected);
            if(jdbc.queryForObject("SELECT count(*) FROM application.applications WHERE organization_id=? AND kubernetes_target_id=? AND deleted_at IS NULL",Long.class,org,id)>0)
                throw new ApplicationException(CONFLICT,"Kubernetes target is used by an application. Move or delete the application first.");
            jdbc.update("""
                UPDATE application.kubernetes_targets SET deleted_at=?,updated_at=?,version=version+1,
                    encrypted_credentials=NULL,encryption_key_version=NULL WHERE organization_id=? AND id=?
                """,Timestamp.from(now),Timestamp.from(now),org,id);
        });
    }
    private void lock(UUID org,UUID id,long expected) {
        var versions=jdbc.queryForList("SELECT version FROM application.kubernetes_targets WHERE organization_id=? AND id=? AND deleted_at IS NULL FOR UPDATE",Long.class,org,id);
        if(versions.isEmpty()) throw missing();
        if(versions.getFirst()!=expected) throw new ApplicationException(CONFLICT,"Kubernetes target version changed. Reload and retry.");
    }
    private KubernetesTarget row(ResultSet rs,int n)throws SQLException {
        return KubernetesTarget.builder().id(rs.getObject("id",UUID.class)).organizationId(rs.getObject("organization_id",UUID.class))
                .name(rs.getString("name")).apiServer(rs.getString("api_server")).namespace(rs.getString("namespace"))
                .credentials(new KubernetesTarget.Credentials(rs.getString("encrypted_credentials"),rs.getString("encryption_key_version")))
                .status(KubernetesTarget.Status.valueOf(rs.getString("status"))).checkCode(rs.getString("check_code")).version(rs.getLong("version"))
                .createdAt(rs.getTimestamp("created_at").toInstant()).updatedAt(rs.getTimestamp("updated_at").toInstant())
                .checkedAt(rs.getTimestamp("checked_at")==null?null:rs.getTimestamp("checked_at").toInstant()).build();
    }
    private static Timestamp timestamp(Instant value) { return value==null?null:Timestamp.from(value); }
    private static ApplicationException missing() { return new ApplicationException(NOT_FOUND,"Kubernetes target not found."); }
}
