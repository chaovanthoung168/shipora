package com.shipora.worker.adapter.out.docker;
import com.shipora.pipeline.Protocol.*;
import com.shipora.worker.application.port.*;
import com.shipora.worker.configuration.WorkerProperties;
import com.shipora.worker.domain.ExecutionFailure;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class DockerRuntime implements RuntimeDeployer {
 private final RemoteDocker transport;private final WorkerProperties properties;private final JsonMapper json=JsonMapper.builder().build();
 public DockerRuntime(RemoteDocker transport,WorkerProperties properties){this.transport=transport;this.properties=properties;}
 public boolean supports(String value){return "DOCKER".equals(value);}
 public Handle deploy(Job job,ApplicationContext context,ServerContext server,String image,Guard guard){
  if(server==null||!job.deployment().organizationId().equals(server.organizationId())||!job.deployment().configuration().serverId().equals(server.id()))throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");
  var state=new State(job,context,server,image);
  call(state,"deploy",guard);return state;
 }
 public String verify(Handle handle,Guard guard){
  var state=(State)handle;var result=call(state,"verify",guard);int port=((Number)result.get("port")).intValue();
  if(port<1||port>65535)throw new ExecutionFailure("HEALTH_CHECK_FAILED");
  String host=state.server().host().contains(":")?"["+state.server().host()+"]":state.server().host();return "http://"+host+":"+port;
 }
 public void rollback(Handle handle,Guard guard){call((State)handle,"rollback",guard);}
 private Map<String,Object> call(State state,String action,Guard guard){
  guard.check();var d=state.job().deployment();var config=d.configuration();
  var body=new LinkedHashMap<String,Object>();body.put("action",action);body.put("organization",d.organizationId());body.put("application",d.applicationId());body.put("deployment",d.id());
  body.put("fence",state.job().fence());body.put("image",state.image());body.put("port",config.containerPort());body.put("healthPath",config.healthPath());
  body.put("timeout",(int)properties.rolloutTimeout().toSeconds());body.put("environment",state.context().environment());body.put("registryAuth",registryAuth());
  return transport.execute(state.server(),body,guard);
 }
 private Map<String,String> registryAuth(){
  if(properties.dockerConfig()==null)return Map.of();
  try{
   Path file=properties.dockerConfig().resolve("config.json");if(!Files.isRegularFile(file))return Map.of();
   if(Files.size(file)>1048576)throw new IllegalArgumentException();String registry=properties.imageRepository().split("/")[0];
   String auth=json.readTree(Files.readAllBytes(file)).path("auths").path(registry).path("auth").asText("");if(auth.isBlank())return Map.of();
   String value=new String(Base64.getDecoder().decode(auth),StandardCharsets.UTF_8);int colon=value.indexOf(':');if(colon<1)throw new IllegalArgumentException();
   return Map.of("username",value.substring(0,colon),"password",value.substring(colon+1),"serveraddress",registry);
  }catch(Exception e){throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");}
 }
 private record State(Job job,ApplicationContext context,ServerContext server,String image)implements Handle{@Override public String toString(){return "DockerState[redacted]";}}
}
