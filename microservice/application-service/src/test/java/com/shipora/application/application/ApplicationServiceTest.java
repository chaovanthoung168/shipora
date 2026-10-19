package com.shipora.application.application;

import com.shipora.application.application.dto.*;
import com.shipora.application.application.port.out.*;
import com.shipora.application.application.service.ApplicationService;
import com.shipora.application.domain.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationServiceTest {
    final ApplicationStore store=mock(ApplicationStore.class);
    final ResourceCatalog catalog=mock(ResourceCatalog.class);
    final SecretCipher cipher=mock(SecretCipher.class);
    final Instant now=Instant.parse("2026-10-18T00:00:00Z");
    final UUID org=UUID.randomUUID(),project=UUID.randomUUID(),repo=UUID.randomUUID(),server=UUID.randomUUID(),id=UUID.randomUUID();
    final ApplicationService service=new ApplicationService(store,catalog,cipher,Clock.fixed(now,ZoneOffset.UTC));
    CreateApplication input(String name){return new CreateApplication(project,repo,server,name,"web",null,null,null,3000,null,null);}
    ManagedApplication app(){return ManagedApplication.builder().id(id).organizationId(org).projectId(project).repositoryId(repo).serverId(server)
        .name("Web").slug("web").branch("main").dockerfilePath("Dockerfile").buildContext(".").containerPort(3000).healthPath("/")
        .autoDeploy(false).version(0).createdAt(now).updatedAt(now).build();}
    @Test void createUsesTrustedOrganizationRepositoryDefaultBranchAndSafeDefaults(){
        when(catalog.requireRepository(org,repo)).thenReturn("develop");when(store.create(any())).thenAnswer(i->i.getArgument(0));
        var result=service.create(org,input("Web"));
        assertEquals(org,result.organizationId());assertEquals("develop",result.branch());assertFalse(result.autoDeploy());
        assertEquals("Dockerfile",result.dockerfilePath());assertEquals(0,result.version());
        verify(catalog).requireProject(org,project);verify(catalog).requireActiveServer(org,server);
    }
    @Test void invalidInputNeverCallsRemoteServicesOrDatabase(){
        assertThrows(ApplicationException.class,()->service.create(org,input(" ")));verifyNoInteractions(store,catalog,cipher);
    }
    @Test void inaccessibleReferencePreventsPersistence(){
        doThrow(new ApplicationException(ApplicationException.Kind.NOT_FOUND,"missing")).when(catalog).requireProject(org,project);
        assertThrows(ApplicationException.class,()->service.create(org,input("Web")));verifyNoInteractions(store);
    }
    @Test void missingApplicationCannotTriggerReferenceChecks(){
        when(store.find(org,id)).thenReturn(Optional.empty());
        assertThrows(ApplicationException.class,()->service.update(org,id,patch(0,"Renamed")));
        verifyNoInteractions(catalog);
    }
    @Test void stalePatchIsRejectedBeforeCallingRemoteServices(){
        when(store.find(org,id)).thenReturn(Optional.of(app()));
        assertThrows(ApplicationException.class,()->service.update(org,id,patch(1,"Renamed")));verifyNoInteractions(catalog);
    }
    @Test void patchPreservesOmittedFieldsAndRevalidatesReferences(){
        when(store.find(org,id)).thenReturn(Optional.of(app()));when(store.update(any(),eq(0L))).thenAnswer(i->i.getArgument(0));
        var result=service.update(org,id,patch(0,"Renamed"));
        assertEquals("Renamed",result.name());assertEquals("main",result.branch());assertEquals(1,result.version());
        verify(catalog).requireProject(org,project);verify(catalog).requireRepository(org,repo);verify(catalog).requireActiveServer(org,server);
    }
    @Test void secretOnlyCrossesStoreBoundaryAsCiphertext(){
        when(store.find(org,id)).thenReturn(Optional.of(app()));
        var encrypted=new SecretCipher.Encrypted("ciphertext","v1");
        when(cipher.encrypt(org,id,"TOKEN","private-value")).thenReturn(encrypted);
        service.putSecret(org,id,0,"TOKEN","private-value");verify(store).putSecret(org,id,0,"TOKEN",encrypted,now);
        assertFalse(encrypted.toString().contains("ciphertext"));
    }
    @Test void crossTenantSecretWriteCannotUseTheCipher(){
        when(store.find(org,id)).thenReturn(Optional.empty());
        assertThrows(ApplicationException.class,()->service.putSecret(org,id,0,"TOKEN","private-value"));verifyNoInteractions(cipher);
    }
    @Test void deleteAndEnvironmentMutationsRequireValidVersions(){
        assertThrows(ApplicationException.class,()->service.delete(org,id,-1));
        assertThrows(ApplicationException.class,()->service.replaceEnvironment(org,id,0,Map.of("BAD-KEY","x")));
        verifyNoInteractions(store);service.delete(org,id,3);verify(store).delete(org,id,3,now);
    }
    UpdateApplication patch(long version,String name){return new UpdateApplication(version,null,null,name,null,null,null,null,null,null,null);}
}
