package com.shipora.deployment.application;
import com.shipora.deployment.application.port.out.*;
import com.shipora.deployment.application.service.AutomaticPushService;
import com.shipora.deployment.domain.*;
import com.shipora.pipeline.Protocol;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AutomaticPushServiceTest {
 final PushInboxStore inbox=mock(PushInboxStore.class);final DeploymentStore jobs=mock(DeploymentStore.class);final AutomaticCatalog catalog=mock(AutomaticCatalog.class);
 final AutomaticPushService service=new AutomaticPushService(inbox,jobs,catalog);
 final UUID org=UUID.randomUUID(),app=UUID.randomUUID(),repo=UUID.randomUUID();final Protocol.Push push=new Protocol.Push(UUID.randomUUID(),repo,"a".repeat(40),"main");
 PushInboxStore.Pending pending(List<UUID> targets){return new PushInboxStore.Pending(org,push,UUID.randomUUID(),targets,0,0);}
 ApplicationSnapshot snapshot(boolean enabled,String branch){return new ApplicationSnapshot(app,org,UUID.randomUUID(),repo,"DOCKER",UUID.randomUUID(),null,"Web","web",branch,"Dockerfile",".",3000,"/",3,enabled);}
 @Test void snapshotsMatchingTargetsBeforeFanout(){var pending=pending(null);when(inbox.claim(org)).thenReturn(Optional.of(pending));when(catalog.matching(org,repo,"main")).thenReturn(List.of(app));service.dispatch(org);verify(inbox).targets(pending,List.of(app));verifyNoInteractions(jobs);}
 @Test void durableFanoutCreatesGitPushJobWithStableIdempotencyKey(){var pending=pending(List.of(app));when(inbox.claim(org)).thenReturn(Optional.of(pending));when(catalog.configuration(org,app)).thenReturn(snapshot(true,"main"));when(jobs.create(any())).thenAnswer(i->i.getArgument(0));
  service.dispatch(org);verify(jobs).create(argThat(d->d.getTriggerType().equals("GIT_PUSH")&&d.getRequestKey().equals("push:"+push.eventId()+":"+app)&&d.getCommitSha().equals(push.commitSha())));verify(inbox).advance(pending);
 }
 @Test void changedBranchOrDisabledApplicationIsSkipped(){for(var snapshot:List.of(snapshot(false,"main"),snapshot(true,"develop"))){reset(inbox,jobs);var pending=pending(List.of(app));when(inbox.claim(org)).thenReturn(Optional.of(pending));when(catalog.configuration(org,app)).thenReturn(snapshot);service.dispatch(org);verify(inbox).advance(pending);verifyNoInteractions(jobs);}}
 @Test void failureRetainsCursorForRetry(){var pending=pending(List.of(app));when(inbox.claim(org)).thenReturn(Optional.of(pending));when(catalog.configuration(org,app)).thenThrow(new IllegalStateException("fixture"));service.dispatch(org);verify(inbox).postpone(pending);verify(inbox,never()).advance(any());verifyNoInteractions(jobs);}
 @Test void rejectsIncompleteOrMalformedPushBeforePersistence(){assertThrows(DeploymentException.class,()->service.accept(org,new Protocol.Push(null,repo,"abc","main")));verifyNoInteractions(inbox);}
}
