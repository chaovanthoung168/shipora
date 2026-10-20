package com.shipora.deployment.application;

import com.shipora.deployment.application.dto.*;
import com.shipora.deployment.application.port.out.*;
import com.shipora.deployment.application.service.DeploymentService;
import com.shipora.deployment.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentServiceTest {
    final UUID org=UUID.randomUUID(),app=UUID.randomUUID();
    final DeploymentStore store=mock(DeploymentStore.class);final DeploymentCatalog catalog=mock(DeploymentCatalog.class);
    final DeploymentService service=new DeploymentService(store,catalog);
    final String commit="a".repeat(40);
    ApplicationSnapshot snapshot(){return new ApplicationSnapshot(app,org,UUID.randomUUID(),UUID.randomUUID(),"DOCKER",UUID.randomUUID(),null,"Web","web","main","Dockerfile",".",3000,"/",4);}
    Deployment existing(){return Deployment.builder().id(UUID.randomUUID()).organizationId(org).applicationId(app).requestKey("request-1").commitSha(commit)
            .configuration(snapshot()).status(DeploymentStatus.QUEUED).build();}
    @Test void createsImmutableMetadataSnapshotAndManualJob(){
        var snapshot=snapshot();when(catalog.requireReadyApplication(org,app)).thenReturn(snapshot);when(store.create(any())).thenAnswer(i->i.getArgument(0));
        var result=service.create(org,"developer","request-1",new CreateDeployment(app,commit));
        assertEquals(DeploymentStatus.QUEUED,result.status());assertEquals(snapshot,result.configuration());assertEquals("MANUAL",result.triggerType());assertEquals(4,result.configuration().version());
    }
    @Test void replayReturnsOriginalWithoutCatalogCallsEvenIfConfigurationChanged(){
        var original=existing();when(store.findByKey(org,"request-1")).thenReturn(Optional.of(original));
        assertEquals(original.getId(),service.create(org,"developer","request-1",new CreateDeployment(app,commit)).id());verifyNoInteractions(catalog);
    }
    @Test void reusedKeyWithDifferentPayloadConflicts(){
        when(store.findByKey(org,"request-1")).thenReturn(Optional.of(existing()));
        assertEquals(DeploymentException.Kind.CONFLICT,assertThrows(DeploymentException.class,()->service.create(org,"developer","request-1",new CreateDeployment(app,"b".repeat(40)))).getKind());verifyNoInteractions(catalog);
    }
    @Test void invalidInputCannotQueryCatalogOrQueue(){
        assertThrows(DeploymentException.class,()->service.create(org,"developer","request-1",new CreateDeployment(app,"abc")));
        assertThrows(DeploymentException.class,()->service.create(org,"developer"," ",new CreateDeployment(app,commit)));verifyNoInteractions(store,catalog);
    }
    @Test void retryOnlyAcceptsOwnedFailedDeploymentAndSnapshotsCurrentConfig(){
        var failed=existing().toBuilder().status(DeploymentStatus.FAILED).build();when(store.find(org,failed.getId())).thenReturn(Optional.of(failed));
        when(catalog.requireReadyApplication(org,app)).thenReturn(snapshot());when(store.create(any())).thenAnswer(i->i.getArgument(0));
        var retry=service.retry(org,"developer",failed.getId(),"retry-1");assertEquals(failed.getId(),retry.retryOf());assertEquals("RETRY",retry.triggerType());assertNotEquals(failed.getId(),retry.id());
        when(store.find(org,failed.getId())).thenReturn(Optional.of(failed.toBuilder().status(DeploymentStatus.SUCCEEDED).build()));
        assertThrows(DeploymentException.class,()->service.retry(org,"developer",failed.getId(),"retry-2"));
        assertEquals(DeploymentException.Kind.NOT_FOUND,assertThrows(DeploymentException.class,()->service.retry(UUID.randomUUID(),"developer",failed.getId(),"retry-3")).getKind());
    }
    @Test void workerTokenRecordsAreRedacted(){
        UUID token=UUID.randomUUID();assertFalse(new JobClaim(UUID.randomUUID(),token,null,1,null).toString().contains(token.toString()));
        assertFalse(new ChangeDeploymentStatus(token,DeploymentStatus.PREPARING,DeploymentStatus.BUILDING,null,null).toString().contains(token.toString()));
    }
    @Test void processEventsAreBoundedBeforePersistenceAndUseTrustedWorkerIdentity(){
        UUID job=UUID.randomUUID(),token=UUID.randomUUID();var events=List.of(new WorkerEvent("INFO","npm run build"));
        service.appendEvents(org,"worker",job,token,events);verify(store).appendEvents(org,"worker",job,token,events);
        for(var invalid:List.of(List.of(new WorkerEvent("DEBUG","line")),List.of(new WorkerEvent("INFO","x".repeat(2049))),List.of(new WorkerEvent("INFO","line\nsecret"))))
            assertThrows(DeploymentException.class,()->service.appendEvents(org,"worker",job,token,invalid));
        assertThrows(DeploymentException.class,()->service.appendEvents(org,"worker",job,token,Collections.nCopies(51,new WorkerEvent("INFO","line"))));
        assertFalse(new WorkerEvent("INFO","private-value").toString().contains("private-value"));
    }
}
