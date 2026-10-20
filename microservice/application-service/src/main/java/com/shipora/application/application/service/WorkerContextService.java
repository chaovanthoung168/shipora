package com.shipora.application.application.service;
import com.shipora.application.application.port.out.*;
import com.shipora.application.domain.*;
import com.shipora.pipeline.Protocol;
import lombok.RequiredArgsConstructor;
import java.util.*;
@RequiredArgsConstructor
public final class WorkerContextService {
 private final WorkerConfigurationPort configurations;private final SecretCipher cipher;
 private final KubernetesTargetStore targets;private final KubernetesCredentialVault vault;
 public Protocol.ApplicationContext context(Protocol.Job job){
  var d=job.deployment();var config=d.configuration();
  var values=configurations.workerConfiguration(d.organizationId(),d.applicationId(),config.version());
  var environment=new TreeMap<>(values.environment());
  values.secrets().forEach((key,value)->environment.put(key,cipher.decrypt(d.organizationId(),d.applicationId(),key,value)));
  Protocol.KubernetesConnection kubernetes=null;
  if("KUBERNETES".equals(config.runtimeType())){
   var target=targets.find(d.organizationId(),config.kubernetesTargetId()).orElseThrow(()->new ApplicationException(ApplicationException.Kind.NOT_FOUND,"Kubernetes target not found."));
   if(target.getStatus()!=KubernetesTarget.Status.ACTIVE)throw new ApplicationException(ApplicationException.Kind.CONFLICT,"Kubernetes target is not ACTIVE.");
   var access=vault.decrypt(d.organizationId(),target.getId(),target.getCredentials());
   kubernetes=new Protocol.KubernetesConnection(access.apiServer(),access.namespace(),access.token(),access.certificateAuthorityData());
  }
  return new Protocol.ApplicationContext(values.version(),environment,kubernetes);
 }
}
