package com.shipora.application.application;

import com.shipora.application.application.port.in.KubernetesTargetUseCases.*;
import com.shipora.application.application.port.out.*;
import com.shipora.application.application.service.KubernetesTargetService;
import com.shipora.application.domain.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubernetesTargetServiceTest {
    final UUID org=UUID.randomUUID(),id=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-19T00:00:00Z");
    final KubernetesTargetStore store=mock(KubernetesTargetStore.class);
    final KubernetesCredentialVault vault=mock(KubernetesCredentialVault.class);
    final KubernetesProbe probe=mock(KubernetesProbe.class);
    final KubernetesTargetService service=new KubernetesTargetService(store,vault,probe,Clock.fixed(now,ZoneOffset.UTC));
    final KubernetesTarget.Credentials credentials=new KubernetesTarget.Credentials("cipher","v1");
    KubernetesTarget target(){return KubernetesTarget.builder().id(id).organizationId(org).name("Cluster").apiServer("https://cluster")
            .namespace("shipora").credentials(credentials).status(KubernetesTarget.Status.ACTIVE).version(3).checkedAt(now).createdAt(now).updatedAt(now).build();}
    void existing(){when(store.find(org,id)).thenReturn(Optional.of(target()));when(store.update(any(),eq(3L))).thenAnswer(i->i.getArgument(0));}
    @Test void createEncryptsCredentialsAndReturnsOnlyUnverifiedMetadata() {
        when(vault.encrypt(eq(org),any(),any())).thenReturn(credentials);when(store.create(any())).thenAnswer(i->i.getArgument(0));
        var result=service.create(org,new Create("Cluster","https://cluster/","shipora","private-token",null));
        assertEquals(KubernetesTarget.Status.UNVERIFIED,result.status());assertEquals(0,result.version());
        assertFalse(result.toString().contains("private-token"));assertFalse(result.toString().contains("cipher"));
        verify(vault).encrypt(eq(org),eq(result.id()),eq(new KubernetesAccess("https://cluster","shipora","private-token","")));
        verifyNoInteractions(probe);
    }
    @Test void rotatingCredentialsInvalidatesPreviousCheckAndPreservesOmittedCa() {
        existing();when(vault.decrypt(org,id,credentials)).thenReturn(new KubernetesAccess("https://cluster","shipora","old-token","Y2E="));
        when(vault.encrypt(eq(org),eq(id),any())).thenReturn(new KubernetesTarget.Credentials("new-cipher","v1"));
        var result=service.update(org,id,new Update(3,null,"new-token",null));
        assertEquals(KubernetesTarget.Status.UNVERIFIED,result.status());assertNull(result.checkedAt());assertNull(result.checkCode());assertEquals(4,result.version());
        verify(vault).encrypt(org,id,new KubernetesAccess("https://cluster","shipora","new-token","Y2E="));
    }
    @Test void renamingPreservesVerifiedStateWithoutDecryptingCredentials() {
        existing();var result=service.update(org,id,new Update(3,"Renamed",null,null));
        assertEquals("Renamed",result.name());assertEquals(KubernetesTarget.Status.ACTIVE,result.status());assertEquals(now,result.checkedAt());verifyNoInteractions(vault,probe);
    }
    @Test void failedCheckMarksErrorAndDoesNotReturnRemoteDetails() {
        existing();when(probe.check(any())).thenReturn(new KubernetesProbe.Result(false,KubernetesProbe.Code.ACCESS_DENIED));
        var result=service.check(org,id,3);assertEquals(KubernetesTarget.Status.ERROR,result.status());assertEquals("ACCESS_DENIED",result.checkCode());assertEquals(4,result.version());
    }
    @Test void oldCheckCannotApproveRotatedCredentials() {
        existing();when(probe.check(any())).thenReturn(new KubernetesProbe.Result(true,KubernetesProbe.Code.OK));
        when(store.update(any(),eq(3L))).thenThrow(new ApplicationException(ApplicationException.Kind.CONFLICT,"Version changed"));
        assertEquals(ApplicationException.Kind.CONFLICT,assertThrows(ApplicationException.class,()->service.check(org,id,3)).getKind());
        verify(store).update(argThat(t->t.getStatus()==KubernetesTarget.Status.ACTIVE),eq(3L));
    }
    @Test void tenantOrVersionFailureNeverDecryptsOrConnects() {
        assertThrows(ApplicationException.class,()->service.check(org,id,0));existing();
        assertThrows(ApplicationException.class,()->service.update(org,id,new Update(2,null,"token",null)));
        assertThrows(ApplicationException.class,()->service.check(org,id,2));verifyNoInteractions(vault,probe);
    }
    @Test void concurrentChecksAreRejectedAndSlotIsReleasedAfterFailure()throws Exception {
        existing();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(probe.check(any())).thenAnswer(i->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new KubernetesProbe.Result(false,KubernetesProbe.Code.UNREACHABLE);});
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->service.check(org,id,3));assertTrue(entered.await(5,TimeUnit.SECONDS));
            try { assertEquals(ApplicationException.Kind.CONFLICT,assertThrows(ApplicationException.class,()->service.check(org,id,3)).getKind()); }
            finally { release.countDown(); }
            assertEquals("UNREACHABLE",first.get(5,TimeUnit.SECONDS).checkCode());
            assertEquals("UNREACHABLE",service.check(org,id,3).checkCode());
        }
    }
}
