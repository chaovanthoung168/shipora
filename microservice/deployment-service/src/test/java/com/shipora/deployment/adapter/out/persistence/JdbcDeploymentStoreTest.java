package com.shipora.deployment.adapter.out.persistence;

import com.shipora.deployment.application.dto.*;
import com.shipora.deployment.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DEPLOYMENT_TEST_JDBC_URL",matches=".+")
class JdbcDeploymentStoreTest {
    JdbcTemplate jdbc;JdbcDeploymentStore store;
    final UUID org=UUID.randomUUID(),app=UUID.randomUUID();final String image="registry.example/shipora/web@sha256:"+"a".repeat(64);
    @BeforeEach void setup(){
        String url=System.getenv("DEPLOYMENT_TEST_JDBC_URL");assertTrue(url.matches("jdbc:postgresql://[^/]+/shipora_deployment_test_[a-z0-9_]+"),"Use an explicitly disposable database.");
        var source=new DriverManagerDataSource(url,System.getenv().getOrDefault("DEPLOYMENT_TEST_DB_USER","postgres"),System.getenv().getOrDefault("DEPLOYMENT_TEST_DB_PASSWORD",""));
        jdbc=new JdbcTemplate(source);jdbc.execute("DROP SCHEMA IF EXISTS deployment CASCADE");jdbc.execute("CREATE SCHEMA deployment");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_deployment_tables.sql"),new ClassPathResource("db/migration/V2__durable_tenant_queue.sql"),new ClassPathResource("db/migration/V3__execution_fencing.sql"),new ClassPathResource("db/migration/V4__automatic_push_inbox.sql"),new ClassPathResource("db/migration/V5__worker_presence.sql")).execute(source);
        store=new JdbcDeploymentStore(jdbc,new DataSourceTransactionManager(source),JsonMapper.builder().build());
    }
    Deployment request(UUID app,String key){return Deployment.builder().id(UUID.randomUUID()).organizationId(org).applicationId(app).requestKey(key).requestedBy("developer")
        .commitSha("a".repeat(40)).branch("main").triggerType("MANUAL").status(DeploymentStatus.QUEUED)
        .configuration(new ApplicationSnapshot(app,org,UUID.randomUUID(),UUID.randomUUID(),"KUBERNETES",null,UUID.randomUUID(),"Web","web","main","Dockerfile",".",3000,"/",5)).build();}
    JobClaim claim(){return store.claim(org,"worker-a").orElseThrow();}
    Deployment stage(JobClaim job,DeploymentStatus old,DeploymentStatus next){return store.progress(org,"worker-a",job.jobId(),new ChangeDeploymentStatus(job.leaseToken(),old,next,next==DeploymentStatus.DEPLOYING?image:null,null));}
    void expire(JobClaim job){jdbc.update("UPDATE deployment.deployment_jobs SET locked_until=clock_timestamp()-interval '1 second' WHERE id=?",job.jobId());}
    @Test void createsJobAndEventAtomicallyAndReplaysWithoutDuplicates(){
        var request=request(app,"key-1");var first=store.create(request);var replay=store.create(request.toBuilder().id(UUID.randomUUID()).build());
        assertEquals(first.getId(),replay.getId());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM deployment.deployment_jobs",Integer.class));
        assertEquals(1,store.events(org,first.getId(),0,100).size());assertEquals(5,first.getConfiguration().version());
        assertThrows(DeploymentException.class,()->store.create(request.toBuilder().id(UUID.randomUUID()).commitSha("b".repeat(40)).build()));
    }
    @Test void allReadsMutationsAndWorkerClaimsAreTenantScoped(){
        var d=store.create(request(app,"key-1"));UUID other=UUID.randomUUID();assertTrue(store.find(other,d.getId()).isEmpty());assertTrue(store.list(other,null,0,20).isEmpty());
        assertTrue(store.findByKey(other,"key-1").isEmpty());assertTrue(store.claim(other,"worker").isEmpty());assertTrue(store.releases(other,app,0,20).isEmpty());
        assertThrows(DeploymentException.class,()->store.events(other,d.getId(),0,100));assertThrows(DeploymentException.class,()->store.cancel(other,d.getId(),0));
        var job=claim();assertThrows(DeploymentException.class,()->store.heartbeat(other,"worker-a",job.jobId(),job.leaseToken()));
    }
    @Test void concurrentClaimsOnlyRunOneJobPerApplication()throws Exception{
        store.create(request(app,"key-1"));store.create(request(app,"key-2"));var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<Future<Optional<JobClaim>>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return store.claim(org,"worker");}));
            start.countDown();int count=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS).isPresent())count++;assertEquals(1,count);
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM deployment.deployment_jobs WHERE status='RUNNING'",Integer.class));
    }
    @Test void differentApplicationsCanRunInParallelAndQueueOrderIsPreserved(){
        var first=store.create(request(app,"key-1"));store.create(request(app,"key-2"));var third=store.create(request(UUID.randomUUID(),"key-3"));
        assertEquals(first.getId(),claim().deployment().id());assertEquals(third.getId(),claim().deployment().id());assertTrue(store.claim(org,"worker").isEmpty());
    }
    @Test void expiredPreparationIsReassignedWithNewTokenAndOldWorkerIsFenced(){
        store.create(request(app,"key-1"));var first=claim();expire(first);var second=store.claim(org,"worker-b").orElseThrow();
        assertEquals(first.jobId(),second.jobId());assertNotEquals(first.leaseToken(),second.leaseToken());assertEquals(2,second.attempt());
        assertThrows(DeploymentException.class,()->store.heartbeat(org,"worker-a",first.jobId(),first.leaseToken()));
        assertThrows(DeploymentException.class,()->stage(first,DeploymentStatus.PREPARING,DeploymentStatus.BUILDING));
        assertEquals(DeploymentStatus.PREPARING,store.heartbeat(org,"worker-b",second.jobId(),second.leaseToken()).status());
    }
    @Test void preparationRecoveryStopsAtMaximumAttempts(){
        var d=store.create(request(app,"key-1"));for(int attempt=1;attempt<=3;attempt++){var job=claim();assertEquals(attempt,job.attempt());expire(job);}
        assertTrue(store.claim(org,"worker").isEmpty());var failed=store.find(org,d.getId()).orElseThrow();assertEquals(DeploymentStatus.FAILED,failed.getStatus());assertEquals(FailureCode.ATTEMPTS_EXHAUSTED,failed.getFailureCode());
    }
    @Test void leaseLossAfterPreparationFailsWithoutAutomaticallyRepeatingSideEffects(){
        var d=store.create(request(app,"key-1"));var job=claim();stage(job,DeploymentStatus.PREPARING,DeploymentStatus.BUILDING);expire(job);
        assertThrows(DeploymentException.class,()->store.heartbeat(org,"worker-a",job.jobId(),job.leaseToken()));assertTrue(store.claim(org,"worker-b").isEmpty());
        var failed=store.find(org,d.getId()).orElseThrow();assertEquals(DeploymentStatus.FAILED,failed.getStatus());assertEquals(FailureCode.LEASE_EXPIRED,failed.getFailureCode());
    }
    @Test void wrongOwnerTokenAndSkippedStagesCannotMutateJob(){
        var d=store.create(request(app,"key-1"));var job=claim();
        assertThrows(DeploymentException.class,()->store.heartbeat(org,"worker-b",job.jobId(),job.leaseToken()));assertThrows(DeploymentException.class,()->store.heartbeat(org,"worker-a",job.jobId(),UUID.randomUUID()));
        assertThrows(DeploymentException.class,()->stage(job,DeploymentStatus.PREPARING,DeploymentStatus.SUCCEEDED));
        var advanced=stage(job,DeploymentStatus.PREPARING,DeploymentStatus.BUILDING);
        assertEquals(advanced.getVersion(),stage(job,DeploymentStatus.PREPARING,DeploymentStatus.BUILDING).getVersion());
        assertThrows(DeploymentException.class,()->stage(job,DeploymentStatus.PREPARING,DeploymentStatus.PUSHING));
        assertEquals(DeploymentStatus.BUILDING,store.find(org,d.getId()).orElseThrow().getStatus());
    }
    void succeed(JobClaim job){stage(job,DeploymentStatus.PREPARING,DeploymentStatus.BUILDING);stage(job,DeploymentStatus.BUILDING,DeploymentStatus.PUSHING);
        stage(job,DeploymentStatus.PUSHING,DeploymentStatus.DEPLOYING);stage(job,DeploymentStatus.DEPLOYING,DeploymentStatus.VERIFYING);stage(job,DeploymentStatus.VERIFYING,DeploymentStatus.SUCCEEDED);}
    @Test void successfulVerificationActivatesReleaseAndRetainsPreviousRelease(){
        var first=store.create(request(app,"key-1"));var job=claim();succeed(job);
        stage(job,DeploymentStatus.VERIFYING,DeploymentStatus.SUCCEEDED);
        assertEquals(DeploymentStatus.SUCCEEDED,store.find(org,first.getId()).orElseThrow().getStatus());assertThrows(DeploymentException.class,()->store.heartbeat(org,"worker-a",job.jobId(),job.leaseToken()));
        var second=store.create(request(app,"key-2"));succeed(claim());var releases=store.releases(org,app,0,20);
        assertEquals(2,releases.size());assertEquals(1,releases.stream().filter(r->r.status().equals("ACTIVE")).count());
        assertEquals(second.getId(),releases.stream().filter(r->r.status().equals("ACTIVE")).findFirst().orElseThrow().deploymentId());assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM deployment.deployment_artifacts",Integer.class));
        assertTrue(store.releases(UUID.randomUUID(),app,0,20).isEmpty());
    }
    @Test void failureDoesNotReplaceActiveReleaseAndEventsAreCursorPaginated(){
        var first=store.create(request(app,"key-1"));succeed(claim());var failed=store.create(request(app,"key-2"));var job=claim();
        store.progress(org,"worker-a",job.jobId(),new ChangeDeploymentStatus(job.leaseToken(),DeploymentStatus.PREPARING,DeploymentStatus.FAILED,null,FailureCode.SOURCE_UNAVAILABLE));
        assertEquals(first.getId(),store.releases(org,app,0,20).getFirst().deploymentId());
        var events=store.events(org,failed.getId(),0,2);assertEquals(2,events.size());var later=store.events(org,failed.getId(),events.getLast().id(),100);assertEquals(1,later.size());assertEquals("FAILED",later.getFirst().eventType());
    }
    @Test void cancellationRequiresCurrentQueuedVersionAndPreventsClaim(){
        var d=store.create(request(app,"key-1"));assertThrows(DeploymentException.class,()->store.cancel(org,d.getId(),1));
        assertEquals(DeploymentStatus.CANCELLED,store.cancel(org,d.getId(),0).getStatus());assertTrue(store.claim(org,"worker").isEmpty());
        var running=store.create(request(app,"key-2"));var job=claim();assertThrows(DeploymentException.class,()->store.cancel(org,running.getId(),job.deployment().version()));
    }
    @Test void concurrentIdempotentCreationProducesOneJob()throws Exception{
        var request=request(app,"same-key");var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var first=pool.submit(()->{start.await();return store.create(request);});var second=pool.submit(()->{start.await();return store.create(request.toBuilder().id(UUID.randomUUID()).build());});
            start.countDown();assertEquals(first.get(10,TimeUnit.SECONDS).getId(),second.get(10,TimeUnit.SECONDS).getId());
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM deployment.deployment_jobs",Integer.class));
    }
    @Test void managedRowsWithoutOrganizationAreRejected(){
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->store.create(request(app,"key-1").toBuilder().organizationId(null).build()));
    }
    @Test void migrationPreservesLegacyHistoryWithoutGivingItAnOwnerOrClaimableJob(){
        jdbc.execute("DROP SCHEMA deployment CASCADE");jdbc.execute("CREATE SCHEMA deployment");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_deployment_tables.sql")).execute(jdbc.getDataSource());
        UUID legacy=UUID.randomUUID();jdbc.update("INSERT INTO deployment.deployments(id,application_id,commit_sha,trigger_type,status) VALUES(?,?,?,'MANUAL','SUCCESS')",legacy,app,"a".repeat(40));
        jdbc.update("INSERT INTO deployment.deployment_jobs(deployment_id) VALUES(?)",legacy);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__durable_tenant_queue.sql"),new ClassPathResource("db/migration/V3__execution_fencing.sql"),new ClassPathResource("db/migration/V4__automatic_push_inbox.sql"),new ClassPathResource("db/migration/V5__worker_presence.sql")).execute(jdbc.getDataSource());
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM deployment.deployments WHERE id=?",String.class,legacy));
        assertNull(jdbc.queryForObject("SELECT organization_id FROM deployment.deployments WHERE id=?",UUID.class,legacy));
        assertTrue(store.find(org,legacy).isEmpty());assertTrue(store.list(org,null,0,20).isEmpty());assertTrue(store.claim(org,"worker").isEmpty());
    }
    @Test void cancellationAndClaimRaceHasExactlyOneWinner()throws Exception{
        var d=store.create(request(app,"key-1"));var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var cancel=pool.submit(()->{start.await();try{store.cancel(org,d.getId(),0);return true;}catch(DeploymentException e){assertEquals(DeploymentException.Kind.CONFLICT,e.getKind());return false;}});
            var claim=pool.submit(()->{start.await();return store.claim(org,"worker").isPresent();});
            start.countDown();assertNotEquals(cancel.get(10,TimeUnit.SECONDS),claim.get(10,TimeUnit.SECONDS));
        }
    }
    @Test void authorizationRequiresCurrentLeaseAndExecutionFencesIncreaseAcrossApplications(){
        store.create(request(app,"first"));var first=claim();assertTrue(first.fence()>0);
        assertEquals(first.fence(),store.authorize(org,"worker-a",first.jobId(),first.leaseToken()).fence());
        assertThrows(DeploymentException.class,()->store.authorize(org,"worker-b",first.jobId(),first.leaseToken()));
        store.create(request(UUID.randomUUID(),"second"));var second=claim();assertTrue(second.fence()>first.fence());expire(first);
        assertThrows(DeploymentException.class,()->store.authorize(org,"worker-a",first.jobId(),first.leaseToken()));
    }
    @Test void pushInboxPersistsTargetCursorAndDeduplicatesPayloadsAcrossRetries(){
        var inbox=new JdbcPushInbox(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),JsonMapper.builder().build());
        var push=new com.shipora.pipeline.Protocol.Push(UUID.randomUUID(),UUID.randomUUID(),"a".repeat(40),"main");
        inbox.accept(org,push);inbox.accept(org,push);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM deployment.push_inbox",Integer.class));assertTrue(inbox.claim(UUID.randomUUID()).isEmpty());
        assertThrows(DeploymentException.class,()->inbox.accept(org,new com.shipora.pipeline.Protocol.Push(push.eventId(),push.repositoryId(),"b".repeat(40),"main")));
        var pending=inbox.claim(org).orElseThrow();assertTrue(inbox.claim(org).isEmpty());inbox.targets(pending,List.of(app,UUID.randomUUID()));
        var first=inbox.claim(org).orElseThrow();assertEquals(app,first.targets().get(first.cursor()));inbox.advance(first);
        var second=inbox.claim(org).orElseThrow();assertEquals(1,second.cursor());inbox.advance(second);assertTrue(inbox.claim(org).isEmpty());
    }
    @Test void processOutputRequiresTheCurrentOwnedLeaseAndDoesNotChangeDeploymentVersion(){
        var deployment=store.create(request(app,"logs"));var job=claim();long version=store.find(org,deployment.getId()).orElseThrow().getVersion();
        var lines=List.of(new WorkerEvent("INFO","npm run build"),new WorkerEvent("WARN","Package notice"));
        store.appendEvents(org,"worker-a",job.jobId(),job.leaseToken(),lines);
        assertEquals(2,store.events(org,deployment.getId(),0,100).stream().filter(e->e.eventType().equals("PROCESS_OUTPUT")).count());
        assertEquals(version,store.find(org,deployment.getId()).orElseThrow().getVersion());
        assertThrows(DeploymentException.class,()->store.appendEvents(UUID.randomUUID(),"worker-a",job.jobId(),job.leaseToken(),lines));
        assertThrows(DeploymentException.class,()->store.appendEvents(org,"worker-b",job.jobId(),job.leaseToken(),lines));
        assertThrows(DeploymentException.class,()->store.appendEvents(org,"worker-a",job.jobId(),UUID.randomUUID(),lines));
        expire(job);assertThrows(DeploymentException.class,()->store.appendEvents(org,"worker-a",job.jobId(),job.leaseToken(),lines));
    }
    @Test void queueDiagnosticsDistinguishMissingWorkersFromApplicationSerializationWithoutTenantLeaks(){
        var first=store.create(request(app,"first"));assertEquals("NO_WORKER",store.queueStatus(org,first.getId()).reason());
        store.claim(UUID.randomUUID(),"other-tenant");assertNull(store.queueStatus(org,first.getId()).lastWorkerSeenAt());
        var job=claim();var next=store.create(request(app,"next"));assertEquals("APPLICATION_BUSY",store.queueStatus(org,next.getId()).reason());
        var other=store.create(request(UUID.randomUUID(),"other-app"));assertEquals("WAITING_FOR_CLAIM",store.queueStatus(org,other.getId()).reason());
        assertThrows(DeploymentException.class,()->store.queueStatus(UUID.randomUUID(),next.getId()));
        expire(job);jdbc.update("UPDATE deployment.worker_presence SET last_seen_at=clock_timestamp()-interval '1 minute' WHERE organization_id=?",org);
        assertEquals("NO_WORKER",store.queueStatus(org,other.getId()).reason());
    }
    @Test void processOutputStorageIsCappedAndTheLimitWarningIsWrittenOnce(){
        var deployment=store.create(request(app,"bounded"));var job=claim();
        jdbc.update("INSERT INTO deployment.deployment_events(deployment_id,level,event_type,message) SELECT ?,'INFO','PROCESS_OUTPUT','line' FROM generate_series(1,5000)",deployment.getId());
        var lines=List.of(new WorkerEvent("INFO","extra"));store.appendEvents(org,"worker-a",job.jobId(),job.leaseToken(),lines);store.appendEvents(org,"worker-a",job.jobId(),job.leaseToken(),lines);
        assertEquals(5000,jdbc.queryForObject("SELECT count(*) FROM deployment.deployment_events WHERE deployment_id=? AND event_type='PROCESS_OUTPUT'",Integer.class,deployment.getId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM deployment.deployment_events WHERE deployment_id=? AND event_type='PROCESS_LOG_LIMIT'",Integer.class,deployment.getId()));
    }
}
