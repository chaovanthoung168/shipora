package com.shipora.deployment.adapter.out.persistence;

import com.shipora.deployment.application.dto.*;
import com.shipora.deployment.application.port.out.DeploymentStore;
import com.shipora.deployment.application.service.DeploymentService;
import com.shipora.deployment.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.shipora.deployment.domain.DeploymentRules.*;

@Repository
public class JdbcDeploymentStore implements DeploymentStore {
    private static final int LEASE_SECONDS=60;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    public JdbcDeploymentStore(JdbcTemplate jdbc,PlatformTransactionManager manager,JsonMapper json){
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.json=json;
    }
    @Override public Optional<Deployment> find(UUID org,UUID id){
        return jdbc.query("SELECT * FROM deployment.deployments WHERE organization_id=? AND id=?",this::deployment,org,id).stream().findFirst();
    }
    @Override public Optional<Deployment> findByKey(UUID org,String key){
        return jdbc.query("SELECT * FROM deployment.deployments WHERE organization_id=? AND request_key=?",this::deployment,org,key).stream().findFirst();
    }
    @Override public Deployment create(Deployment d){
        return tx.execute(status->{
            // Lock the slot before creating a job. All managed queue writes use this order.
            jdbc.update("INSERT INTO deployment.application_slots(organization_id,application_id) VALUES(?,?) ON CONFLICT DO NOTHING",d.getOrganizationId(),d.getApplicationId());
            lockSlot(d.getOrganizationId(),d.getApplicationId());
            Instant now=now();
            int inserted=jdbc.update("""
                INSERT INTO deployment.deployments(id,organization_id,application_id,commit_sha,branch,trigger_type,requested_by,
                    request_key,configuration,retry_of,status,version,created_at)
                VALUES(?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,'QUEUED',0,?)
                ON CONFLICT (organization_id,request_key) WHERE organization_id IS NOT NULL DO NOTHING
                """,d.getId(),d.getOrganizationId(),d.getApplicationId(),d.getCommitSha(),d.getBranch(),d.getTriggerType(),d.getRequestedBy(),
                    d.getRequestKey(),json.writeValueAsString(d.getConfiguration()),d.getRetryOf(),timestamp(now));
            if(inserted==0){
                var existing=findByKey(d.getOrganizationId(),d.getRequestKey()).orElseThrow(JdbcDeploymentStore::missing);
                DeploymentService.sameRequest(existing,d.getApplicationId(),d.getCommitSha(),d.getRetryOf());
                return existing;
            }
            jdbc.update("INSERT INTO deployment.deployment_jobs(id,deployment_id,managed,status,available_at,created_at) VALUES(?,?,TRUE,'PENDING',?,?)",
                    UUID.randomUUID(),d.getId(),timestamp(now),timestamp(now));
            event(d.getId(),"INFO","QUEUED","Deployment queued.",now);
            return require(d.getOrganizationId(),d.getId());
        });
    }
    @Override public List<Deployment> list(UUID org,UUID app,int offset,int limit){
        return app==null?jdbc.query("SELECT * FROM deployment.deployments WHERE organization_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",this::deployment,org,limit,offset):
                jdbc.query("SELECT * FROM deployment.deployments WHERE organization_id=? AND application_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",this::deployment,org,app,limit,offset);
    }
    @Override public Deployment cancel(UUID org,UUID id,long expected){
        return tx.execute(status->{
            var original=require(org,id);lockSlot(org,original.getApplicationId());
            var job=jobForDeployment(org,id);
            if(job.deployment().getVersion()!=expected)throw conflict("Deployment version changed. Reload and retry.");
            if(job.deployment().getStatus()!=DeploymentStatus.QUEUED || !job.status().equals("PENDING"))throw conflict("Only queued deployments can be cancelled.");
            var now=now();
            jdbc.update("UPDATE deployment.deployment_jobs SET status='CANCELLED',locked_by=NULL,locked_until=NULL,lease_token=NULL WHERE id=?",job.id());
            jdbc.update("UPDATE deployment.deployments SET status='CANCELLED',version=version+1,finished_at=? WHERE id=? AND organization_id=?",timestamp(now),id,org);
            event(id,"INFO","CANCELLED","Queued deployment cancelled.",now);return require(org,id);
        });
    }
    @Override public List<DeploymentEvent> events(UUID org,UUID id,long after,int limit){
        require(org,id);
        return jdbc.query("""
            SELECT e.* FROM deployment.deployment_events e JOIN deployment.deployments d ON d.id=e.deployment_id
            WHERE d.organization_id=? AND d.id=? AND e.id>? ORDER BY e.id LIMIT ?
            """,(rs,n)->new DeploymentEvent(rs.getLong("id"),rs.getObject("deployment_id",UUID.class),rs.getString("level"),
                rs.getString("event_type"),rs.getString("message"),rs.getTimestamp("created_at").toInstant()),org,id,after,limit);
    }
    @Override public List<ReleaseResult> releases(UUID org,UUID app,int offset,int limit){
        return jdbc.query("""
            SELECT r.* FROM deployment.releases r JOIN deployment.deployments d ON d.id=r.deployment_id
            WHERE d.organization_id=? AND r.application_id=? ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?
            """,(rs,n)->new ReleaseResult(rs.getObject("id",UUID.class),rs.getObject("application_id",UUID.class),rs.getObject("deployment_id",UUID.class),
                rs.getString("image_reference"),rs.getString("status"),instant(rs,"activated_at"),instant(rs,"created_at"),rs.getString("runtime_endpoint")),org,app,limit,offset);
    }
    @Override public QueueStatus queueStatus(UUID org,UUID id){
        var deployment=require(org,id);Instant now=now();
        var seen=jdbc.queryForObject("SELECT max(last_seen_at) FROM deployment.worker_presence WHERE organization_id=?",Timestamp.class,org);
        var running=jdbc.queryForList("""
            SELECT d.application_id FROM deployment.deployment_jobs j JOIN deployment.deployments d ON d.id=j.deployment_id
            WHERE d.organization_id=? AND j.managed AND j.status='RUNNING' AND j.locked_until>?
            """,UUID.class,org,timestamp(now));
        String reason=deployment.getStatus()!=DeploymentStatus.QUEUED?"NOT_QUEUED":running.contains(deployment.getApplicationId())?"APPLICATION_BUSY":
                running.isEmpty()&&(seen==null||seen.toInstant().isBefore(now.minusSeconds(30)))?"NO_WORKER":"WAITING_FOR_CLAIM";
        return new QueueStatus(reason,seen==null?null:seen.toInstant(),running.size(),deployment.getCreatedAt());
    }
    @Override public void appendEvents(UUID org,String worker,UUID jobId,UUID token,List<WorkerEvent> events){
        tx.executeWithoutResult(status->{
            var job=lockJob(org,jobId);Instant now=now();validLease(job,worker,token,now);
            UUID deployment=job.deployment().getId();
            int count=jdbc.queryForObject("SELECT count(*) FROM deployment.deployment_events WHERE deployment_id=? AND event_type='PROCESS_OUTPUT'",Integer.class,deployment);
            int accepted=Math.min(events.size(),Math.max(0,5000-count));
            for(var entry:events.subList(0,accepted))event(deployment,entry.level(),"PROCESS_OUTPUT",entry.message(),now);
            if(accepted<events.size()&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deployment.deployment_events WHERE deployment_id=? AND event_type='PROCESS_LOG_LIMIT')",Boolean.class,deployment)))
                event(deployment,"WARN","PROCESS_LOG_LIMIT","Process output limit reached; subsequent build output is omitted.",now);
        });
    }
    @Override public Optional<JobClaim> claim(UUID org,String worker){
        return tx.execute(status->{
            // Bounded recovery work per poll; unrelated application slots never wait.
            recordPresence(org,worker,now());
            for(int scan=0;scan<8;scan++){
                Instant now=now();
                var apps=jdbc.queryForList("""
                    SELECT s.application_id FROM deployment.application_slots s
                    WHERE s.organization_id=? AND EXISTS (
                        SELECT 1 FROM deployment.deployments d JOIN deployment.deployment_jobs j ON j.deployment_id=d.id
                        WHERE d.organization_id=s.organization_id AND d.application_id=s.application_id AND j.managed
                          AND ((j.status='PENDING' AND j.available_at<=?) OR (j.status='RUNNING' AND j.locked_until<=?))
                    ) AND NOT EXISTS (
                        SELECT 1 FROM deployment.deployments d JOIN deployment.deployment_jobs j ON j.deployment_id=d.id
                        WHERE d.organization_id=s.organization_id AND d.application_id=s.application_id AND j.managed
                          AND j.status='RUNNING' AND j.locked_until>?
                    ) ORDER BY s.created_at,s.application_id FOR UPDATE OF s SKIP LOCKED LIMIT 1
                    """,UUID.class,org,timestamp(now),timestamp(now),timestamp(now));
                if(apps.isEmpty())return Optional.empty();
                UUID app=apps.getFirst();
                var expired=jdbc.query(jobSelect()+"""
                    WHERE d.organization_id=? AND d.application_id=? AND j.managed AND j.status='RUNNING' AND j.locked_until<=?
                    ORDER BY j.created_at,j.id FOR UPDATE OF j,d
                    """,this::job,org,app,timestamp(now));
                for(var job:expired)recover(job,now);
                var ready=jdbc.query(jobSelect()+"""
                    WHERE d.organization_id=? AND d.application_id=? AND j.managed AND j.status='PENDING' AND j.available_at<=?
                    ORDER BY d.created_at,d.id LIMIT 1 FOR UPDATE OF j,d
                    """,this::job,org,app,timestamp(now));
                if(ready.isEmpty())continue;
                var job=ready.getFirst();
                if(job.attempts()>=job.maxAttempts()){failExpired(job,FailureCode.ATTEMPTS_EXHAUSTED,now);continue;}
                UUID token=UUID.randomUUID();Instant until=now.plusSeconds(LEASE_SECONDS);
                long fence=jdbc.queryForObject("SELECT nextval('deployment.execution_fences')",Long.class);
                jdbc.update("UPDATE deployment.deployment_jobs SET status='RUNNING',attempts=attempts+1,locked_by=?,last_worker=?,locked_until=?,lease_token=?,execution_fence=?,last_error=NULL WHERE id=?",
                        worker,worker,timestamp(until),token,fence,job.id());
                jdbc.update("UPDATE deployment.deployments SET status='PREPARING',version=version+1,started_at=COALESCE(started_at,?),finished_at=NULL,failure_code=NULL WHERE id=?",
                        timestamp(now),job.deployment().getId());
                event(job.deployment().getId(),"INFO","CLAIMED","Worker claimed deployment attempt "+(job.attempts()+1)+".",now);
                return Optional.of(new JobClaim(job.id(),token,until,job.attempts()+1,DeploymentResult.from(require(org,job.deployment().getId())),fence));
            }
            return Optional.empty();
        });
    }
    @Override public LeaseResult heartbeat(UUID org,String worker,UUID jobId,UUID token){
        return tx.execute(status->{
            var job=lockJob(org,jobId);Instant now=now();validLease(job,worker,token,now);
            recordPresence(org,worker,now);
            Instant until=now.plusSeconds(LEASE_SECONDS);
            jdbc.update("UPDATE deployment.deployment_jobs SET locked_until=? WHERE id=?",timestamp(until),jobId);
            return new LeaseResult(jobId,until,job.deployment().getStatus(),job.deployment().getVersion());
        });
    }
    @Override public JobClaim authorize(UUID org,String worker,UUID jobId,UUID token){
        return tx.execute(status->{var job=lockJob(org,jobId);validLease(job,worker,token,now());
            long fence=jdbc.queryForObject("SELECT execution_fence FROM deployment.deployment_jobs WHERE id=?",Long.class,jobId);
            return new JobClaim(jobId,token,job.until(),job.attempts(),DeploymentResult.from(job.deployment()),fence);});
    }
    @Override public Deployment progress(UUID org,String worker,UUID jobId,ChangeDeploymentStatus in){
        return tx.execute(status->{
            var job=lockJob(org,jobId);Instant now=now();var d=job.deployment();
            if(d.getStatus()==in.status() && Objects.equals(worker,job.owner()) && Objects.equals(in.leaseToken(),job.token())){
                if(job.status().equals("RUNNING"))validLease(job,worker,in.leaseToken(),now);
                else if(!Set.of("DONE","FAILED").contains(job.status()))throw conflict("Job lease is no longer valid.");
                transition(in.expectedStatus(),in.status(),in.imageReference(),in.failureCode());
                if(!Objects.equals(d.getFailureCode(),in.failureCode()) || !Objects.equals(d.getRuntimeEndpoint(),in.runtimeEndpoint()) || (in.status()==DeploymentStatus.DEPLOYING && !Objects.equals(d.getImageReference(),in.imageReference())))
                    throw conflict("A different result was already recorded for this stage.");
                return d;
            }
            validLease(job,worker,in.leaseToken(),now);
            if(d.getStatus()!=in.expectedStatus())throw conflict("Deployment status changed. Reload and retry.");
            transition(d.getStatus(),in.status(),in.imageReference(),in.failureCode());
            String image=in.imageReference()==null?d.getImageReference():in.imageReference();
            if(in.status()==DeploymentStatus.SUCCEEDED && image==null)throw conflict("A verified image digest is required before success.");
            jdbc.update("""
                UPDATE deployment.deployments SET status=?,version=version+1,image_reference=?,failure_code=?,finished_at=?,runtime_endpoint=? WHERE id=? AND organization_id=?
                """,in.status().name(),image,in.failureCode()==null?null:in.failureCode().name(),in.status().terminal()?timestamp(now):null,in.runtimeEndpoint(),d.getId(),org);
            if(in.status()==DeploymentStatus.DEPLOYING)jdbc.update("""
                INSERT INTO deployment.deployment_artifacts(deployment_id,artifact_type,artifact_reference,created_at) VALUES(?,'DOCKER_IMAGE',?,?)
                """,d.getId(),image,timestamp(now));
            if(in.status().terminal()){
                // Retain the completed attempt token only for idempotent PUT retries.
                jdbc.update("UPDATE deployment.deployment_jobs SET status=?,locked_by=NULL,locked_until=NULL,last_error=? WHERE id=?",
                        in.status()==DeploymentStatus.SUCCEEDED?"DONE":"FAILED",in.failureCode()==null?null:in.failureCode().name(),jobId);
                if(in.status()==DeploymentStatus.SUCCEEDED){
                    jdbc.update("""
                        UPDATE deployment.releases r SET status='PREVIOUS' FROM deployment.deployments d
                        WHERE r.deployment_id=d.id AND d.organization_id=? AND r.application_id=? AND r.status='ACTIVE'
                        """,org,d.getApplicationId());
                    jdbc.update("INSERT INTO deployment.releases(application_id,deployment_id,image_reference,status,activated_at,created_at,runtime_endpoint) VALUES(?,?,?,'ACTIVE',?,?,?)",
                            d.getApplicationId(),d.getId(),image,timestamp(now),timestamp(now),in.runtimeEndpoint());
                }
            }
            String message=in.status()==DeploymentStatus.FAILED?"Worker reported failure: "+in.failureCode().name()+".":"Deployment entered "+in.status().name()+".";
            event(d.getId(),in.status()==DeploymentStatus.FAILED?"ERROR":"INFO",in.status().name(),message,now);
            return require(org,d.getId());
        });
    }
    private void recover(Job job,Instant now){
        if(job.deployment().getStatus()==DeploymentStatus.PREPARING && job.attempts()<job.maxAttempts()){
            jdbc.update("UPDATE deployment.deployment_jobs SET status='PENDING',locked_by=NULL,locked_until=NULL,lease_token=NULL,available_at=?,last_error='LEASE_EXPIRED' WHERE id=?",timestamp(now),job.id());
            jdbc.update("UPDATE deployment.deployments SET status='QUEUED',version=version+1 WHERE id=?",job.deployment().getId());
            event(job.deployment().getId(),"WARN","LEASE_RECOVERED","Preparation lease expired; job made available again.",now);
        }else failExpired(job,job.attempts()>=job.maxAttempts()?FailureCode.ATTEMPTS_EXHAUSTED:FailureCode.LEASE_EXPIRED,now);
    }
    private void failExpired(Job job,FailureCode code,Instant now){
        jdbc.update("UPDATE deployment.deployment_jobs SET status='FAILED',locked_by=NULL,locked_until=NULL,lease_token=NULL,last_error=? WHERE id=?",code.name(),job.id());
        jdbc.update("UPDATE deployment.deployments SET status='FAILED',failure_code=?,version=version+1,finished_at=? WHERE id=?",code.name(),timestamp(now),job.deployment().getId());
        event(job.deployment().getId(),"ERROR",code.name(),"Worker lease ended; deployment requires an explicit retry and runtime reconciliation.",now);
    }
    private Job lockJob(UUID org,UUID jobId){
        var apps=jdbc.queryForList("""
            SELECT d.application_id FROM deployment.deployment_jobs j JOIN deployment.deployments d ON d.id=j.deployment_id
            WHERE d.organization_id=? AND j.id=? AND j.managed
            """,UUID.class,org,jobId);
        if(apps.isEmpty())throw missing();lockSlot(org,apps.getFirst());
        return jdbc.query(jobSelect()+"WHERE d.organization_id=? AND j.id=? AND j.managed FOR UPDATE OF j,d",this::job,org,jobId).stream().findFirst().orElseThrow(JdbcDeploymentStore::missing);
    }
    private Job jobForDeployment(UUID org,UUID id){
        return jdbc.query(jobSelect()+"WHERE d.organization_id=? AND d.id=? AND j.managed FOR UPDATE OF j,d",this::job,org,id).stream().findFirst().orElseThrow(JdbcDeploymentStore::missing);
    }
    private void validLease(Job job,String worker,UUID token,Instant now){
        if(!job.status().equals("RUNNING") || !Objects.equals(worker,job.owner()) || !Objects.equals(token,job.token()) || job.until()==null || !job.until().isAfter(now))
            throw conflict("Job lease is no longer valid.");
    }
    private void lockSlot(UUID org,UUID app){
        jdbc.queryForList("SELECT application_id FROM deployment.application_slots WHERE organization_id=? AND application_id=? FOR UPDATE",UUID.class,org,app);
    }
    private void recordPresence(UUID org,String worker,Instant now){
        jdbc.update("INSERT INTO deployment.worker_presence(organization_id,worker_name,last_seen_at) VALUES(?,?,?) ON CONFLICT(organization_id,worker_name) DO UPDATE SET last_seen_at=EXCLUDED.last_seen_at",org,worker,timestamp(now));
    }
    private void event(UUID id,String level,String type,String message,Instant now){
        jdbc.update("INSERT INTO deployment.deployment_events(deployment_id,level,event_type,message,created_at) VALUES(?,?,?,?,?)",id,level,type,message,timestamp(now));
    }
    private Deployment require(UUID org,UUID id){return find(org,id).orElseThrow(JdbcDeploymentStore::missing);}
    private static DeploymentException missing(){return new DeploymentException(DeploymentException.Kind.NOT_FOUND,"Deployment not found.");}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static Timestamp timestamp(Instant value){return Timestamp.from(value);}
    private static Instant instant(ResultSet rs,String key)throws SQLException{return rs.getTimestamp(key)==null?null:rs.getTimestamp(key).toInstant();}
    private Deployment deployment(ResultSet rs,int n)throws SQLException{
        return Deployment.builder().id(rs.getObject("id",UUID.class)).organizationId(rs.getObject("organization_id",UUID.class)).applicationId(rs.getObject("application_id",UUID.class))
                .commitSha(rs.getString("commit_sha")).branch(rs.getString("branch")).triggerType(rs.getString("trigger_type")).requestedBy(rs.getString("requested_by"))
                .requestKey(rs.getString("request_key")).retryOf(rs.getObject("retry_of",UUID.class)).runtimeEndpoint(rs.getString("runtime_endpoint")).status(DeploymentStatus.valueOf(rs.getString("status")))
                .failureCode(rs.getString("failure_code")==null?null:FailureCode.valueOf(rs.getString("failure_code"))).imageReference(rs.getString("image_reference"))
                .configuration(json.readValue(rs.getString("configuration"),ApplicationSnapshot.class)).version(rs.getLong("version"))
                .createdAt(instant(rs,"created_at")).startedAt(instant(rs,"started_at")).finishedAt(instant(rs,"finished_at")).build();
    }
    private static String jobSelect(){return """
        SELECT d.*,j.id AS job_id,j.status AS job_status,j.attempts AS job_attempts,j.max_attempts AS job_max_attempts,
            COALESCE(j.locked_by,j.last_worker) AS job_owner,j.locked_until AS job_until,j.lease_token AS job_token
        FROM deployment.deployment_jobs j JOIN deployment.deployments d ON d.id=j.deployment_id
        """;}
    private Job job(ResultSet rs,int n)throws SQLException{return new Job(rs.getObject("job_id",UUID.class),rs.getString("job_status"),rs.getInt("job_attempts"),
            rs.getInt("job_max_attempts"),rs.getString("job_owner"),instant(rs,"job_until"),rs.getObject("job_token",UUID.class),deployment(rs,n));}
    private record Job(UUID id,String status,int attempts,int maxAttempts,String owner,Instant until,UUID token,Deployment deployment){
        @Override public String toString(){return "Job[redacted]";}
    }
}

