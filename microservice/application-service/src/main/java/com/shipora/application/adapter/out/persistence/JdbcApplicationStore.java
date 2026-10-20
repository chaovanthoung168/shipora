package com.shipora.application.adapter.out.persistence;

import com.shipora.application.application.port.out.*;
import com.shipora.application.application.port.in.ApplicationUseCases.*;
import com.shipora.application.domain.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.shipora.application.domain.ApplicationException.Kind.*;

@Repository
public class JdbcApplicationStore implements ApplicationStore,WorkerConfigurationPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public JdbcApplicationStore(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);}
    @Override public ManagedApplication create(ManagedApplication app) {
        return tx.execute(status->{
        lockTarget(app);
        jdbc.update("""
            INSERT INTO application.applications (id,organization_id,project_id,repository_id,server_id,name,slug,branch,
                dockerfile_path,build_context,container_port,health_path,auto_deploy,version,created_at,updated_at,runtime_type,kubernetes_target_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,app.getId(),app.getOrganizationId(),app.getProjectId(),app.getRepositoryId(),app.getServerId(),app.getName(),app.getSlug(),
                app.getBranch(),app.getDockerfilePath(),app.getBuildContext(),app.getContainerPort(),app.getHealthPath(),app.isAutoDeploy(),
                app.getVersion(),Timestamp.from(app.getCreatedAt()),Timestamp.from(app.getUpdatedAt()),app.getRuntimeType().name(),app.getKubernetesTargetId());
        return app;
        });
    }
    @Override public Optional<ManagedApplication> find(UUID org,UUID id){
        return jdbc.query("SELECT * FROM application.applications WHERE organization_id=? AND id=? AND deleted_at IS NULL",this::row,org,id).stream().findFirst();
    }
    @Override public List<ManagedApplication> autoDeployApplications(UUID org,UUID repositoryId,String branch,int offset,int limit){
        return jdbc.query("SELECT * FROM application.applications WHERE organization_id=? AND repository_id=? AND branch=? AND auto_deploy AND deleted_at IS NULL ORDER BY id LIMIT ? OFFSET ?",this::row,org,repositoryId,branch,limit,offset);
    }
    @Override public WorkerConfigurationPort.Values workerConfiguration(UUID org,UUID app,long expected){
        return tx.execute(status->{
            var versions=jdbc.queryForList("SELECT version FROM application.applications WHERE organization_id=? AND id=? AND deleted_at IS NULL FOR SHARE",Long.class,org,app);
            if(versions.isEmpty())throw missing();
            if(versions.getFirst()!=expected)throw new ApplicationException(CONFLICT,"Application configuration changed after this deployment was queued.");
            var env=new TreeMap<String,String>();
            jdbc.query("SELECT key,value FROM application.application_env_vars WHERE application_id=?",(org.springframework.jdbc.core.RowCallbackHandler)rs->env.put(rs.getString("key"),rs.getString("value")),app);
            var secrets=new TreeMap<String,SecretCipher.Encrypted>();
            jdbc.query("SELECT key,encrypted_value,encryption_key_version FROM application.application_secrets WHERE application_id=?",(org.springframework.jdbc.core.RowCallbackHandler)rs->secrets.put(rs.getString("key"),new SecretCipher.Encrypted(rs.getString("encrypted_value"),rs.getString("encryption_key_version"))),app);
            return new WorkerConfigurationPort.Values(expected,env,secrets);
        });
    }
    @Override public List<ManagedApplication> list(UUID org,UUID project,int offset,int limit) {
        return project==null ? jdbc.query("""
            SELECT * FROM application.applications WHERE organization_id=? AND deleted_at IS NULL
            ORDER BY created_at DESC,id LIMIT ? OFFSET ?
            """,this::row,org,limit,offset) : jdbc.query("""
            SELECT * FROM application.applications WHERE organization_id=? AND project_id=? AND deleted_at IS NULL
            ORDER BY created_at DESC,id LIMIT ? OFFSET ?
            """,this::row,org,project,limit,offset);
    }
    @Override public ManagedApplication update(ManagedApplication app,long expected){
        return tx.execute(status->{
            lockTarget(app);
            int changed=jdbc.update("""
                UPDATE application.applications SET repository_id=?,server_id=?,name=?,slug=?,branch=?,dockerfile_path=?,
                build_context=?,container_port=?,health_path=?,auto_deploy=?,version=version+1,updated_at=?,runtime_type=?,kubernetes_target_id=?
                WHERE organization_id=? AND id=? AND version=? AND deleted_at IS NULL
                """,app.getRepositoryId(),app.getServerId(),app.getName(),app.getSlug(),app.getBranch(),app.getDockerfilePath(),
                    app.getBuildContext(),app.getContainerPort(),app.getHealthPath(),app.isAutoDeploy(),Timestamp.from(app.getUpdatedAt()),
                    app.getRuntimeType().name(),app.getKubernetesTargetId(),app.getOrganizationId(),app.getId(),expected);
            if(changed!=1) stale(app.getOrganizationId(),app.getId());
            return find(app.getOrganizationId(),app.getId()).orElseThrow(JdbcApplicationStore::missing);
        });
    }
    @Override public void delete(UUID org,UUID id,long version,Instant now){
        tx.executeWithoutResult(status->{
            guard(org,id,version,now);
            jdbc.update("UPDATE application.applications SET deleted_at=? WHERE id=? AND organization_id=?",Timestamp.from(now),id,org);
            jdbc.update("DELETE FROM application.application_env_vars WHERE application_id=?",id);
            jdbc.update("DELETE FROM application.application_secrets WHERE application_id=?",id);
            jdbc.update("DELETE FROM application.application_domains WHERE application_id=?",id);
        });
    }
    @Override public EnvironmentResult environment(UUID org,UUID id){
        var rows=jdbc.query("""
            SELECT a.version,e.key,e.value FROM application.applications a
            LEFT JOIN application.application_env_vars e ON e.application_id=a.id
            WHERE a.organization_id=? AND a.id=? AND a.deleted_at IS NULL ORDER BY e.key
            """,(rs,n)->new EnvRow(rs.getLong("version"),rs.getString("key"),rs.getString("value")),org,id);
        if(rows.isEmpty())throw missing();
        var values=new TreeMap<String,String>();for(var item:rows)if(item.key()!=null)values.put(item.key(),item.value());
        return new EnvironmentResult(rows.getFirst().version(),values);
    }
    @Override public EnvironmentResult replaceEnvironment(UUID org,UUID id,long version,Map<String,String> values,Instant now){
        return tx.execute(status->{
            guard(org,id,version,now);
            var secretKeys=jdbc.queryForList("SELECT key FROM application.application_secrets WHERE application_id=?",String.class,id);
            if(secretKeys.stream().anyMatch(values::containsKey))throw new ApplicationException(CONFLICT,"An environment key is already configured as a secret.");
            jdbc.update("DELETE FROM application.application_env_vars WHERE application_id=?",id);
            for(var item:values.entrySet()) jdbc.update("INSERT INTO application.application_env_vars (application_id,key,value) VALUES (?,?,?)",id,item.getKey(),item.getValue());
            return environment(org,id);
        });
    }
    @Override public SecretKeysResult secretKeys(UUID org,UUID id){
        var rows=jdbc.query("""
            SELECT a.version,s.key FROM application.applications a LEFT JOIN application.application_secrets s ON s.application_id=a.id
            WHERE a.organization_id=? AND a.id=? AND a.deleted_at IS NULL ORDER BY s.key
            """,(rs,n)->new KeyRow(rs.getLong("version"),rs.getString("key")),org,id);
        if(rows.isEmpty())throw missing();
        return new SecretKeysResult(rows.getFirst().version(),rows.stream().map(KeyRow::key).filter(Objects::nonNull).toList());
    }
    @Override public SecretKeysResult putSecret(UUID org,UUID id,long version,String key,SecretCipher.Encrypted value,Instant now){
        return tx.execute(status->{
            guard(org,id,version,now);
            if(jdbc.queryForObject("SELECT count(*) FROM application.application_env_vars WHERE application_id=? AND key=?",Integer.class,id,key)>0)
                throw new ApplicationException(CONFLICT,"This key is already configured as an environment variable.");
            int count=jdbc.queryForObject("SELECT count(*) FROM application.application_secrets WHERE application_id=?",Integer.class,id);
            if(count>=100 && jdbc.queryForObject("SELECT count(*) FROM application.application_secrets WHERE application_id=? AND key=?",Integer.class,id,key)==0)
                throw new ApplicationException(CONFLICT,"An application can contain at most 100 secrets.");
            jdbc.update("""
                INSERT INTO application.application_secrets (application_id,key,encrypted_value,encryption_key_version,created_at,updated_at)
                VALUES (?,?,?,?,?,?) ON CONFLICT (application_id,key) DO UPDATE SET encrypted_value=EXCLUDED.encrypted_value,
                encryption_key_version=EXCLUDED.encryption_key_version,updated_at=EXCLUDED.updated_at
                """,id,key,value.ciphertext(),value.keyVersion(),Timestamp.from(now),Timestamp.from(now));
            return secretKeys(org,id);
        });
    }
    @Override public SecretKeysResult deleteSecret(UUID org,UUID id,long version,String key,Instant now){
        return tx.execute(status->{guard(org,id,version,now);
            if(jdbc.update("DELETE FROM application.application_secrets WHERE application_id=? AND key=?",id,key)!=1)
                throw new ApplicationException(NOT_FOUND,"Secret key not found.");
            return secretKeys(org,id);
        });
    }
    private void guard(UUID org,UUID id,long expected,Instant now){
        if(jdbc.update("UPDATE application.applications SET version=version+1,updated_at=? WHERE organization_id=? AND id=? AND version=? AND deleted_at IS NULL",
                Timestamp.from(now),org,id,expected)!=1) stale(org,id);
    }
    private void stale(UUID org,UUID id){
        if(find(org,id).isEmpty())throw missing();
        throw new ApplicationException(CONFLICT,"Application version changed. Reload and retry.");
    }
    private static ApplicationException missing(){return new ApplicationException(NOT_FOUND,"Application not found.");}
    private void lockTarget(ManagedApplication app) {
        if(app.getRuntimeType()!=RuntimeType.KUBERNETES)return;
        // Serialize target deletion/credential changes with application creation and reassignment.
        var states=jdbc.queryForList("SELECT status FROM application.kubernetes_targets WHERE organization_id=? AND id=? AND deleted_at IS NULL FOR SHARE",
                String.class,app.getOrganizationId(),app.getKubernetesTargetId());
        if(states.isEmpty())throw new ApplicationException(NOT_FOUND,"Kubernetes target not found.");
        if(!states.getFirst().equals("ACTIVE"))throw new ApplicationException(CONFLICT,"Run a successful Kubernetes target connection check first.");
    }
    private ManagedApplication row(ResultSet rs,int n)throws SQLException {
        return ManagedApplication.builder().id(rs.getObject("id",UUID.class)).organizationId(rs.getObject("organization_id",UUID.class))
                .projectId(rs.getObject("project_id",UUID.class)).repositoryId(rs.getObject("repository_id",UUID.class)).serverId(rs.getObject("server_id",UUID.class))
                .runtimeType(RuntimeType.valueOf(rs.getString("runtime_type"))).kubernetesTargetId(rs.getObject("kubernetes_target_id",UUID.class))
                .name(rs.getString("name")).slug(rs.getString("slug")).branch(rs.getString("branch")).dockerfilePath(rs.getString("dockerfile_path"))
                .buildContext(rs.getString("build_context")).containerPort(rs.getInt("container_port")).healthPath(rs.getString("health_path"))
                .autoDeploy(rs.getBoolean("auto_deploy")).version(rs.getLong("version")).createdAt(rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at").toInstant()).build();
    }
    private record EnvRow(long version,String key,String value){}
    private record KeyRow(long version,String key){}
}
