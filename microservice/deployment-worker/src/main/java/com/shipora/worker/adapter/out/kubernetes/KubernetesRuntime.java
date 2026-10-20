package com.shipora.worker.adapter.out.kubernetes;
import com.shipora.pipeline.*;
import com.shipora.pipeline.Protocol.*;
import com.shipora.worker.application.port.*;
import com.shipora.worker.configuration.WorkerProperties;
import com.shipora.worker.domain.ExecutionFailure;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;

public final class KubernetesRuntime implements RuntimeDeployer {
 private final WorkerProperties properties;private final JsonMapper json=JsonMapper.builder().build();
 public KubernetesRuntime(WorkerProperties properties){this.properties=properties;}
 public boolean supports(String value){return "KUBERNETES".equals(value);}
 public Handle deploy(Job job,ApplicationContext context,ServerContext server,String image,Guard guard){
  var connection=context.kubernetes();validate(connection,job);
  String name="shipora-"+job.deployment().applicationId(),secret=name+"-"+Long.toString(job.fence(),36);
  var handle=new State(job,connection,image,name,secret,null,null);
  try(var http=client(connection)){
   var previous=get(http,connection,deploymentPath(handle));owner(previous,job);
   var service=get(http,connection,servicePath(handle));owner(service,job);
   handle=new State(job,connection,image,name,secret,previous,service);
   if(json.writeValueAsBytes(context.environment()).length>700000)throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");
   write(http,connection,core(connection,"secrets"),secret,Map.of("apiVersion","v1","kind","Secret","metadata",metadata(handle,secret),"type","Opaque","stringData",context.environment()),job,guard);
   var template=new LinkedHashMap<String,Object>();template.put("metadata",Map.of("labels",labels(job)));
   var container=new LinkedHashMap<String,Object>();container.put("name","application");container.put("image",image);container.put("imagePullPolicy","IfNotPresent");
   container.put("ports",List.of(Map.of("containerPort",job.deployment().configuration().containerPort())));
   container.put("envFrom",List.of(Map.of("secretRef",Map.of("name",secret))));
   container.put("readinessProbe",Map.of("httpGet",Map.of("path",job.deployment().configuration().healthPath(),"port",job.deployment().configuration().containerPort()),"periodSeconds",3,"timeoutSeconds",2,"failureThreshold",20));
   container.put("resources",Map.of("requests",Map.of("cpu","100m","memory","128Mi"),"limits",Map.of("cpu","1","memory","1Gi")));
   container.put("securityContext",Map.of("allowPrivilegeEscalation",false,"capabilities",Map.of("drop",List.of("ALL"))));
   var pod=new LinkedHashMap<String,Object>();pod.put("automountServiceAccountToken",false);pod.put("securityContext",Map.of("seccompProfile",Map.of("type","RuntimeDefault")));
   pod.put("containers",List.of(container));pod.put("terminationGracePeriodSeconds",30);
   var registry=registryConfig();
   if(!registry.isEmpty()){
    String pullSecret=pullSecret(handle);
    write(http,connection,core(connection,"secrets"),pullSecret,Map.of("apiVersion","v1","kind","Secret","metadata",metadata(handle,pullSecret),"type","kubernetes.io/dockerconfigjson","stringData",Map.of(".dockerconfigjson",registry)),job,guard);
    pod.put("imagePullSecrets",List.of(Map.of("name",pullSecret)));
   }
   template.put("spec",pod);
   var selector=Map.of("shipora.io/application",job.deployment().applicationId().toString(),"shipora.io/organization",job.deployment().organizationId().toString());
   var spec=Map.of("replicas",1,"revisionHistoryLimit",2,"progressDeadlineSeconds",(int)Math.max(60,properties.rolloutTimeout().toSeconds()),
     "strategy",Map.of("type","RollingUpdate","rollingUpdate",Map.of("maxSurge",1,"maxUnavailable",0)),"selector",Map.of("matchLabels",selector),"template",template);
   write(http,connection,apps(connection),name,Map.of("apiVersion","apps/v1","kind","Deployment","metadata",metadata(handle,name),"spec",spec),job,guard);
   write(http,connection,core(connection,"services"),name,Map.of("apiVersion","v1","kind","Service","metadata",metadata(handle,name),
     "spec",Map.of("type","ClusterIP","selector",selector,"ports",List.of(Map.of("name","http","port",80,"targetPort",job.deployment().configuration().containerPort())))),job,guard);
   if(!properties.baseDomain().isBlank()){
    var ingressSpec=new LinkedHashMap<String,Object>();if(!properties.ingressClass().isBlank())ingressSpec.put("ingressClassName",properties.ingressClass());
    ingressSpec.put("rules",List.of(Map.of("host",host(handle),"http",Map.of("paths",List.of(Map.of("path","/","pathType","Prefix","backend",Map.of("service",Map.of("name",name,"port",Map.of("number",80)))))))));
    write(http,connection,ingresses(connection),name,Map.of("apiVersion","networking.k8s.io/v1","kind","Ingress","metadata",metadata(handle,name),"spec",ingressSpec),job,guard);
   }
   return handle;
  }catch(ExecutionFailure.LeaseLost e){throw e;}
  catch(RuntimeException e){try{rollback(handle,guard);}catch(RuntimeException ignored){}throw new ExecutionFailure("DEPLOY_FAILED");}
 }
 public String verify(Handle value,Guard guard){
  State state=(State)value;long deadline=System.nanoTime()+properties.rolloutTimeout().toNanos();
  try(var http=client(state.connection())){
   while(System.nanoTime()<deadline){
    guard.check();var deployment=get(http,state.connection(),deploymentPath(state));owner(deployment,state.job());
    if(deployment==null)throw new ExecutionFailure("HEALTH_CHECK_FAILED");
    var status=deployment.path("status");long generation=deployment.path("metadata").path("generation").asLong();
    String deployed=deployment.path("spec").path("template").path("spec").path("containers").get(0).path("image").asText();
    if(!deployed.equals(state.image()))throw new ExecutionFailure.LeaseLost();
    if(status.path("observedGeneration").asLong()>=generation&&status.path("updatedReplicas").asInt()==1&&status.path("availableReplicas").asInt()>=1&&status.path("replicas").asInt()==1&&status.path("unavailableReplicas").asInt()==0)
     return properties.baseDomain().isBlank()?"http://"+state.name()+"."+state.connection().namespace()+".svc.cluster.local":"http://"+host(state);
    Thread.sleep(500);
   }
  }catch(ExecutionFailure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ExecutionFailure.LeaseLost();}
  catch(Exception e){throw new ExecutionFailure("HEALTH_CHECK_FAILED");}
  throw new ExecutionFailure("HEALTH_CHECK_FAILED");
 }
 public void rollback(Handle value,Guard guard){
  State state=(State)value;
  try(var http=client(state.connection())){
   guard.check();var current=get(http,state.connection(),deploymentPath(state));
   if(current!=null){
    owner(current,state.job());
    if(current.path("metadata").path("annotations").path("shipora.io/fence").asLong()!=state.job().fence())throw new ExecutionFailure.LeaseLost();
   }
   if(state.previousDeployment()!=null)write(http,state.connection(),apps(state.connection()),state.name(),Map.of("apiVersion","apps/v1","kind","Deployment","metadata",metadata(state,state.name()),"spec",state.previousDeployment().path("spec")),state.job(),guard);
   else deleteOwned(http,state,deploymentPath(state),guard);
   if(state.previousService()!=null)write(http,state.connection(),core(state.connection(),"services"),state.name(),Map.of("apiVersion","v1","kind","Service","metadata",metadata(state,state.name()),"spec",state.previousService().path("spec")),state.job(),guard);
   else deleteOwned(http,state,servicePath(state),guard);
   deleteOwned(http,state,core(state.connection(),"secrets")+"/"+state.secret(),guard);
   deleteOwned(http,state,core(state.connection(),"secrets")+"/"+pullSecret(state),guard);
   if(state.previousDeployment()==null&&!properties.baseDomain().isBlank())deleteOwned(http,state,ingresses(state.connection())+"/"+state.name(),guard);
  }
 }
 private void deleteOwned(InternalHttp http,State state,String path,Guard guard){
  guard.check();var resource=get(http,state.connection(),path);if(resource==null)return;owner(resource,state.job());
  if(resource.path("metadata").path("annotations").path("shipora.io/fence").asLong()!=state.job().fence())return;
  var options=Map.of("apiVersion","v1","kind","DeleteOptions","propagationPolicy","Background","preconditions",Map.of("uid",resource.path("metadata").path("uid").asText(),"resourceVersion",resource.path("metadata").path("resourceVersion").asText()));
  request(http,state.connection(),path,"DELETE",options,guard);
 }
 private void write(InternalHttp http,KubernetesConnection connection,String collection,String name,Object document,Job job,Guard guard){
  for(int attempt=0;attempt<5;attempt++){
   guard.check();var existing=get(http,connection,collection+"/"+name);owner(existing,job);
   var body=json.valueToTree(document).deepCopy();
   if(existing!=null)((tools.jackson.databind.node.ObjectNode)body.path("metadata")).put("resourceVersion",existing.path("metadata").path("resourceVersion").asText());
   try{request(http,connection,existing==null?collection:collection+"/"+name,existing==null?"POST":"PATCH",body,guard);return;}
   catch(InternalHttp.RemoteFailure e){if(e.status!=409)throw e;}
  }
  throw new ExecutionFailure("DEPLOY_FAILED");
 }
 private void owner(JsonNode existing,Job job){
  if(existing==null)return;
  if(!job.deployment().applicationId().toString().equals(existing.path("metadata").path("labels").path("shipora.io/application").asText())
      ||!job.deployment().organizationId().toString().equals(existing.path("metadata").path("labels").path("shipora.io/organization").asText()))throw new ExecutionFailure("DEPLOY_FAILED");
  if(existing.path("metadata").path("annotations").path("shipora.io/fence").asLong()>job.fence())throw new ExecutionFailure.LeaseLost();
 }
 private JsonNode get(InternalHttp http,KubernetesConnection connection,String path){try{return request(http,connection,path,"GET",null,()->{});}catch(InternalHttp.RemoteFailure e){if(e.status==404)return null;throw e;}}
 private JsonNode request(InternalHttp http,KubernetesConnection connection,String path,String method,Object body,Guard guard){
  guard.check();var builder=HttpRequest.newBuilder(URI.create(connection.apiServer().replaceAll("/+$","")+path)).timeout(Duration.ofSeconds(12))
   .header("Authorization","Bearer "+connection.token()).header("Accept","application/json");
  if(body!=null)builder.header("Content-Type",method.equals("PATCH")?"application/merge-patch+json":"application/json");
  return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build());
 }
 private InternalHttp client(KubernetesConnection connection){
  try{
   SSLContext tls=SSLContext.getDefault();
   if(connection.certificateAuthorityData()!=null&&!connection.certificateAuthorityData().isBlank()){
    var certificates=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(Base64.getDecoder().decode(connection.certificateAuthorityData())));
    if(certificates.isEmpty())throw new IllegalArgumentException();var keys=KeyStore.getInstance(KeyStore.getDefaultType());keys.load(null,null);int i=0;
    for(var certificate:certificates)keys.setCertificateEntry("ca-"+i++,certificate);
    var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(keys);tls=SSLContext.getInstance("TLS");tls.init(null,managers.getTrustManagers(),null);
   }
   return new InternalHttp(HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
  }catch(Exception e){throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");}
 }
 private void validate(KubernetesConnection connection,Job job){
  if(connection==null||!InternalHttp.origin(connection.apiServer()).getScheme().equals("https")||!connection.namespace().matches("[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?")
   ||connection.token()==null||!connection.token().matches("[A-Za-z0-9._~+/-]+=*")||job.fence()<1)throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");
  var config=job.deployment().configuration();
  if(config.containerPort()<1||config.containerPort()>65535||config.healthPath()==null||!config.healthPath().matches("/[A-Za-z0-9_./-]*")||config.healthPath().contains("..")||config.healthPath().startsWith("//"))throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");
 }
 private String registryConfig(){
  if(properties.dockerConfig()==null)return "";
  try{
   var file=properties.dockerConfig().resolve("config.json");if(!java.nio.file.Files.isRegularFile(file))return "";
   if(java.nio.file.Files.size(file)>1048576)throw new IllegalArgumentException();var auths=json.readTree(java.nio.file.Files.readAllBytes(file)).path("auths");
   String registry=properties.imageRepository().split("/")[0];var entry=auths.path(registry);
   if(!entry.path("auth").isString())return "";
   return json.writeValueAsString(Map.of("auths",Map.of(registry,Map.of("auth",entry.path("auth").asText()))));
  }catch(Exception e){throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");}
 }
 private Map<String,String> labels(Job job){return Map.of("shipora.io/application",job.deployment().applicationId().toString(),"shipora.io/organization",job.deployment().organizationId().toString(),"shipora.io/deployment",job.deployment().id().toString());}
 private Map<String,Object> metadata(State state,String name){return Map.of("name",name,"namespace",state.connection().namespace(),"labels",labels(state.job()),"annotations",Map.of("shipora.io/fence",Long.toString(state.job().fence())));}
 private String core(KubernetesConnection c,String resource){return "/api/v1/namespaces/"+c.namespace()+"/"+resource;}
 private String apps(KubernetesConnection c){return "/apis/apps/v1/namespaces/"+c.namespace()+"/deployments";}
 private String ingresses(KubernetesConnection c){return "/apis/networking.k8s.io/v1/namespaces/"+c.namespace()+"/ingresses";}
 private String deploymentPath(State s){return apps(s.connection())+"/"+s.name();}
 private String servicePath(State s){return core(s.connection(),"services")+"/"+s.name();}
 private String host(State s){return "app-"+s.job().deployment().applicationId().toString().replace("-","")+"."+properties.baseDomain();}
 private String pullSecret(State s){return "reg-"+s.job().deployment().applicationId().toString().replace("-","")+"-"+Long.toString(s.job().fence(),36);}
 private record State(Job job,KubernetesConnection connection,String image,String name,String secret,JsonNode previousDeployment,JsonNode previousService)implements Handle{
  @Override public String toString(){return "KubernetesState[redacted]";}
 }
}
