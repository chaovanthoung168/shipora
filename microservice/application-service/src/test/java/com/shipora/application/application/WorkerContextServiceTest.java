package com.shipora.application.application;
import com.shipora.application.application.port.out.*;
import com.shipora.application.application.service.WorkerContextService;
import com.shipora.application.domain.*;
import com.shipora.pipeline.Protocol.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WorkerContextServiceTest {
 final WorkerConfigurationPort store=mock(WorkerConfigurationPort.class);final SecretCipher cipher=mock(SecretCipher.class);final KubernetesTargetStore targets=mock(KubernetesTargetStore.class);final KubernetesCredentialVault vault=mock(KubernetesCredentialVault.class);
 final WorkerContextService service=new WorkerContextService(store,cipher,targets,vault);final UUID org=UUID.randomUUID(),app=UUID.randomUUID(),target=UUID.randomUUID();
 Job job(String runtime){var config=new Configuration(app,org,UUID.randomUUID(),UUID.randomUUID(),runtime,null,target,"Web","web","main","Dockerfile",".",3000,"/",7,true);return new Job(UUID.randomUUID(),UUID.randomUUID(),null,1,new DeploymentInfo(UUID.randomUUID(),org,app,"a".repeat(40),"main","MANUAL","PREPARING",config),1);}
 @Test void decryptsOnlyVersionCheckedScopedJobConfiguration(){
  var encrypted=new SecretCipher.Encrypted("cipher","v1");when(store.workerConfiguration(org,app,7)).thenReturn(new WorkerConfigurationPort.Values(7,Map.of("MODE","production"),Map.of("TOKEN",encrypted)));
  when(cipher.decrypt(org,app,"TOKEN",encrypted)).thenReturn("private-value");var result=service.context(job("DOCKER"));assertEquals(Map.of("MODE","production","TOKEN","private-value"),result.environment());assertFalse(result.toString().contains("private-value"));assertNull(result.kubernetes());verifyNoInteractions(targets,vault);
 }
 @Test void staleVersionCannotDecryptAnyCredentials(){
  when(store.workerConfiguration(org,app,7)).thenThrow(new ApplicationException(ApplicationException.Kind.CONFLICT,"Changed"));assertThrows(ApplicationException.class,()->service.context(job("DOCKER")));verifyNoInteractions(cipher,vault);
 }
 @Test void kubernetesAccessRequiresAnActiveOwnedTarget(){
  when(store.workerConfiguration(org,app,7)).thenReturn(new WorkerConfigurationPort.Values(7,Map.of(),Map.of()));
  when(targets.find(org,target)).thenReturn(Optional.of(KubernetesTarget.builder().id(target).status(KubernetesTarget.Status.UNVERIFIED).build()));
  assertThrows(ApplicationException.class,()->service.context(job("KUBERNETES")));verifyNoInteractions(vault);
 }
}
