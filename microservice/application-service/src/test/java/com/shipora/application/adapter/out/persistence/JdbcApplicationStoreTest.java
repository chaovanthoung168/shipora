package com.shipora.application.adapter.out.persistence;

import com.shipora.application.domain.*;
import com.shipora.application.application.port.out.SecretCipher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="APPLICATION_TEST_JDBC_URL",matches=".+")
class JdbcApplicationStoreTest {
    JdbcTemplate jdbc;JdbcApplicationStore store;JdbcKubernetesTargetStore targets;
    final UUID org=UUID.randomUUID(),project=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-18T00:00:00Z");
    @BeforeEach void setup(){
        String url=System.getenv("APPLICATION_TEST_JDBC_URL");
        assertTrue(url.matches("jdbc:postgresql://[^/]+/shipora_application_test_[a-z0-9_]+"),"Use an explicitly disposable database.");
        var source=new DriverManagerDataSource(url,System.getenv().getOrDefault("APPLICATION_TEST_DB_USER","postgres"),System.getenv().getOrDefault("APPLICATION_TEST_DB_PASSWORD",""));
        jdbc=new JdbcTemplate(source);jdbc.execute("DROP SCHEMA IF EXISTS application CASCADE");jdbc.execute("CREATE SCHEMA application");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_application_tables.sql"),
                new ClassPathResource("db/migration/V2__application_ownership_and_versioning.sql"),
                new ClassPathResource("db/migration/V3__kubernetes_targets.sql")).execute(source);
        store=new JdbcApplicationStore(jdbc,new DataSourceTransactionManager(source));
        targets=new JdbcKubernetesTargetStore(jdbc,new DataSourceTransactionManager(source));
    }
    ManagedApplication app(String slug){return ManagedApplication.builder().id(UUID.randomUUID()).organizationId(org).projectId(project).repositoryId(UUID.randomUUID())
        .serverId(UUID.randomUUID()).name("Web").slug(slug).branch("main").dockerfilePath("Dockerfile").buildContext(".").containerPort(3000)
        .healthPath("/").autoDeploy(false).version(0).createdAt(now).updatedAt(now).build();}
    @Test void tenantScopeIsEnforcedForReadsUpdatesDeletesAndConfiguration(){
        var app=store.create(app("web"));UUID other=UUID.randomUUID();
        assertTrue(store.find(other,app.getId()).isEmpty());assertTrue(store.list(other,null,0,20).isEmpty());
        assertEquals(ApplicationException.Kind.NOT_FOUND,assertThrows(ApplicationException.class,()->store.environment(other,app.getId())).getKind());
        assertThrows(ApplicationException.class,()->store.replaceEnvironment(other,app.getId(),0,Map.of("KEY","value"),now));
        assertThrows(ApplicationException.class,()->store.putSecret(other,app.getId(),0,"SECRET",new SecretCipher.Encrypted("cipher","v1"),now));
        assertThrows(ApplicationException.class,()->store.delete(other,app.getId(),0,now));
        assertEquals(0,store.find(org,app.getId()).orElseThrow().getVersion());
    }
    @Test void metadataPatchAndProjectFilterPreserveIdentity(){
        var app=store.create(app("web"));
        var result=store.update(app.toBuilder().name("Renamed").updatedAt(now.plusSeconds(10)).build(),0);
        assertEquals(1,result.getVersion());assertEquals("Renamed",result.getName());assertEquals(project,result.getProjectId());
        assertEquals(1,store.list(org,project,0,20).size());assertTrue(store.list(org,UUID.randomUUID(),0,20).isEmpty());
    }
    @Test void optimisticConcurrencyAllowsOnlyOneWriter()throws Exception{
        var app=store.create(app("web"));var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var tasks=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();try{store.replaceEnvironment(org,app.getId(),0,Map.of("KEY",UUID.randomUUID().toString()),now);return true;}
                catch(ApplicationException e){assertEquals(ApplicationException.Kind.CONFLICT,e.getKind());return false;}}));
            start.countDown();int winners=0;for(var task:tasks)if(task.get(10,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);assertEquals(1,store.environment(org,app.getId()).version());
        }finally{pool.shutdownNow();}
    }
    @Test void secretCollisionRollsBackEnvironmentReplacementAndVersion(){
        var app=store.create(app("web"));
        store.replaceEnvironment(org,app.getId(),0,Map.of("MODE","production"),now);
        store.putSecret(org,app.getId(),1,"TOKEN",new SecretCipher.Encrypted("encrypted","v1"),now);
        assertThrows(ApplicationException.class,()->store.replaceEnvironment(org,app.getId(),2,Map.of("TOKEN","plaintext"),now));
        assertEquals(Map.of("MODE","production"),store.environment(org,app.getId()).variables());
        assertEquals(2,store.environment(org,app.getId()).version());
    }
    @Test void environmentCollisionRollsBackSecretWriteAndVersion(){
        var app=store.create(app("web"));store.replaceEnvironment(org,app.getId(),0,Map.of("MODE","production"),now);
        assertThrows(ApplicationException.class,()->store.putSecret(org,app.getId(),1,"MODE",new SecretCipher.Encrypted("cipher","v1"),now));
        assertTrue(store.secretKeys(org,app.getId()).keys().isEmpty());assertEquals(1,store.secretKeys(org,app.getId()).version());
    }
    @Test void secretUpsertAndDeleteOnlyExposeNamesAndRespectVersions(){
        var app=store.create(app("web"));
        var keys=store.putSecret(org,app.getId(),0,"TOKEN",new SecretCipher.Encrypted("cipher-1","v1"),now);
        assertEquals(List.of("TOKEN"),keys.keys());assertEquals(1,keys.version());
        store.putSecret(org,app.getId(),1,"TOKEN",new SecretCipher.Encrypted("cipher-2","v1"),now.plusSeconds(1));
        assertEquals("cipher-2",jdbc.queryForObject("SELECT encrypted_value FROM application.application_secrets",String.class));
        assertThrows(ApplicationException.class,()->store.deleteSecret(org,app.getId(),1,"TOKEN",now));
        assertTrue(store.deleteSecret(org,app.getId(),2,"TOKEN",now).keys().isEmpty());
    }
    @Test void missingSecretDeletionDoesNotAdvanceVersion(){
        var app=store.create(app("web"));
        assertThrows(ApplicationException.class,()->store.deleteSecret(org,app.getId(),0,"MISSING",now));
        assertEquals(0,store.find(org,app.getId()).orElseThrow().getVersion());
    }
    @Test void softDeleteErasesConfigurationHidesApplicationAndAllowsSlugReuse(){
        var app=store.create(app("web"));store.replaceEnvironment(org,app.getId(),0,Map.of("MODE","production"),now);
        store.putSecret(org,app.getId(),1,"TOKEN",new SecretCipher.Encrypted("cipher","v1"),now);
        store.delete(org,app.getId(),2,now);
        assertTrue(store.find(org,app.getId()).isEmpty());assertTrue(store.list(org,null,0,20).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM application.application_secrets",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM application.application_env_vars",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM application.applications WHERE deleted_at IS NOT NULL",Integer.class));
        assertNotNull(store.create(app("web")));
    }
    @Test void duplicateSlugIsRejectedWithoutChangingExistingApplication(){
        var app=store.create(app("web"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->store.create(app("web")));
        assertEquals(1,store.list(org,null,0,20).size());assertEquals(0,store.find(org,app.getId()).orElseThrow().getVersion());
    }
    @Test void newRowsWithoutAnOrganizationAreRejectedByDatabase(){
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->store.create(app("web").toBuilder().organizationId(null).build()));
    }
    @Test void workerConfigurationIsVersionCheckedTenantScopedAndEncryptedAtTheStoreBoundary(){
        var app=store.create(app("worker"));store.replaceEnvironment(org,app.getId(),0,Map.of("MODE","production"),now);
        store.putSecret(org,app.getId(),1,"TOKEN",new SecretCipher.Encrypted("encrypted-value","v1"),now);
        var context=store.workerConfiguration(org,app.getId(),2);assertEquals(Map.of("MODE","production"),context.environment());assertEquals("encrypted-value",context.secrets().get("TOKEN").ciphertext());
        assertThrows(ApplicationException.class,()->store.workerConfiguration(org,app.getId(),1));assertThrows(ApplicationException.class,()->store.workerConfiguration(UUID.randomUUID(),app.getId(),2));
        assertFalse(context.toString().contains("encrypted-value"));
    }
    @Test void automaticMatchingHonorsOrganizationRepositoryBranchAndOptIn(){
        var candidate=store.create(app("auto").toBuilder().autoDeploy(true).build());store.create(app("manual"));
        assertEquals(1,store.autoDeployApplications(org,candidate.getRepositoryId(),"main",0,100).size());
        assertTrue(store.autoDeployApplications(org,candidate.getRepositoryId(),"develop",0,100).isEmpty());
        assertTrue(store.autoDeployApplications(UUID.randomUUID(),candidate.getRepositoryId(),"main",0,100).isEmpty());
        store.delete(org,candidate.getId(),0,now);assertTrue(store.autoDeployApplications(org,candidate.getRepositoryId(),"main",0,100).isEmpty());
    }
    KubernetesTarget target(KubernetesTarget.Status state){return targets.create(KubernetesTarget.builder().id(UUID.randomUUID()).organizationId(org)
        .name("Cluster-"+UUID.randomUUID()).apiServer("https://cluster").namespace("shipora").credentials(new KubernetesTarget.Credentials("encrypted","v1"))
        .status(state).version(0).createdAt(now).updatedAt(now).build());}
    ManagedApplication kube(String slug,UUID target){return app(slug).toBuilder().runtimeType(RuntimeType.KUBERNETES).serverId(null).kubernetesTargetId(target).build();}
    @Test void kubernetesTargetAndApplicationRoundTripEnforceTenantScope(){
        var target=target(KubernetesTarget.Status.ACTIVE);var application=store.create(kube("kube",target.getId()));
        var loaded=store.find(org,application.getId()).orElseThrow();
        assertEquals(RuntimeType.KUBERNETES,loaded.getRuntimeType());assertNull(loaded.getServerId());assertEquals(target.getId(),loaded.getKubernetesTargetId());
        UUID other=UUID.randomUUID();assertTrue(targets.find(other,target.getId()).isEmpty());assertTrue(targets.list(other,0,20).isEmpty());
        assertThrows(ApplicationException.class,()->targets.delete(other,target.getId(),0,now));
        assertThrows(ApplicationException.class,()->store.create(kube("other",target.getId()).toBuilder().organizationId(other).build()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE application.applications SET organization_id=? WHERE id=?",other,application.getId()));
    }
    @Test void unverifiedAndDeletedTargetsCannotReceiveApplications(){
        var target=target(KubernetesTarget.Status.UNVERIFIED);
        assertEquals(ApplicationException.Kind.CONFLICT,assertThrows(ApplicationException.class,()->store.create(kube("kube",target.getId()))).getKind());
        var docker=store.create(app("docker"));
        assertThrows(ApplicationException.class,()->store.update(docker.toBuilder().runtimeType(RuntimeType.KUBERNETES).serverId(null).kubernetesTargetId(target.getId()).build(),0));
        assertEquals(RuntimeType.DOCKER,store.find(org,docker.getId()).orElseThrow().getRuntimeType());
        targets.delete(org,target.getId(),0,now);
        assertEquals(ApplicationException.Kind.NOT_FOUND,assertThrows(ApplicationException.class,()->store.create(kube("kube",target.getId()))).getKind());
        assertNull(jdbc.queryForObject("SELECT encrypted_credentials FROM application.kubernetes_targets WHERE id=?",String.class,target.getId()));
    }
    @Test void usedTargetsCannotBeDeletedUntilApplicationsAreMovedOrDeleted(){
        var target=target(KubernetesTarget.Status.ACTIVE);var application=store.create(kube("kube",target.getId()));
        assertEquals(ApplicationException.Kind.CONFLICT,assertThrows(ApplicationException.class,()->targets.delete(org,target.getId(),0,now)).getKind());
        assertEquals(0,targets.find(org,target.getId()).orElseThrow().getVersion());
        store.delete(org,application.getId(),0,now);targets.delete(org,target.getId(),0,now);
        assertTrue(targets.find(org,target.getId()).isEmpty());
    }
    @Test void runtimeSwitchPersistsAndDatabaseRejectsAmbiguousDestinations(){
        var target=target(KubernetesTarget.Status.ACTIVE);var docker=store.create(app("docker"));
        var kube=store.update(docker.toBuilder().runtimeType(RuntimeType.KUBERNETES).serverId(null).kubernetesTargetId(target.getId()).build(),0);
        assertEquals(1,kube.getVersion());assertEquals(target.getId(),kube.getKubernetesTargetId());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE application.applications SET server_id=? WHERE id=?",UUID.randomUUID(),docker.getId()));
        var result=store.update(kube.toBuilder().runtimeType(RuntimeType.DOCKER).serverId(UUID.randomUUID()).kubernetesTargetId(null).build(),1);
        assertEquals(RuntimeType.DOCKER,result.getRuntimeType());assertNull(result.getKubernetesTargetId());
        targets.delete(org,target.getId(),0,now);
    }
    @Test void targetVersionPreventsStaleCheckFromOverwritingCredentialRotation(){
        var target=target(KubernetesTarget.Status.ACTIVE);
        var rotated=targets.update(target.toBuilder().credentials(new KubernetesTarget.Credentials("rotated-cipher","v1"))
                .status(KubernetesTarget.Status.UNVERIFIED).checkedAt(null).build(),0);
        assertEquals(1,rotated.getVersion());
        assertEquals(ApplicationException.Kind.CONFLICT,assertThrows(ApplicationException.class,()->targets.update(target,0)).getKind());
        assertEquals(KubernetesTarget.Status.UNVERIFIED,targets.find(org,target.getId()).orElseThrow().getStatus());
        assertEquals("rotated-cipher",targets.find(org,target.getId()).orElseThrow().getCredentials().ciphertext());
        assertThrows(ApplicationException.class,()->targets.delete(org,target.getId(),0,now));
    }
    @Test void concurrentTargetDeletionAndApplicationCreationNeverLeaveAnActiveDanglingReference()throws Exception{
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            for(int iteration=0;iteration<10;iteration++){
                var target=target(KubernetesTarget.Status.ACTIVE);var application=kube("race-"+iteration,target.getId());var start=new CountDownLatch(1);
                var create=pool.submit(()->{start.await();try{store.create(application);return true;}catch(ApplicationException e){assertEquals(ApplicationException.Kind.NOT_FOUND,e.getKind());return false;}});
                var delete=pool.submit(()->{start.await();try{targets.delete(org,target.getId(),0,now);return true;}catch(ApplicationException e){assertEquals(ApplicationException.Kind.CONFLICT,e.getKind());return false;}});
                start.countDown();boolean created=create.get(10,TimeUnit.SECONDS),deleted=delete.get(10,TimeUnit.SECONDS);
                assertNotEquals(created,deleted);assertEquals(created,store.find(org,application.getId()).isPresent());assertEquals(created,targets.find(org,target.getId()).isPresent());
            }
        }
    }
    @Test void migrationKeepsExistingUnownedRowsHiddenWithoutAssigningThemToATenant(){
        jdbc.execute("DROP SCHEMA application CASCADE");jdbc.execute("CREATE SCHEMA application");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_application_tables.sql")).execute(jdbc.getDataSource());
        jdbc.update("""
            INSERT INTO application.applications(project_id,repository_id,server_id,name,slug,container_port)
            VALUES(?,?,?,'Legacy','legacy',3000)
            """,project,UUID.randomUUID(),UUID.randomUUID());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__application_ownership_and_versioning.sql")).execute(jdbc.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__kubernetes_targets.sql")).execute(jdbc.getDataSource());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM application.applications",Integer.class));
        assertTrue(store.list(org,null,0,20).isEmpty());
    }
}
