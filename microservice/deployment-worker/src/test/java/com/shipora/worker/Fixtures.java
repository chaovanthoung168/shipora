package com.shipora.worker;
import com.shipora.pipeline.Protocol.*;
import com.shipora.worker.configuration.WorkerProperties;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
public final class Fixtures {
 public static final UUID ORG=UUID.randomUUID(),APP=UUID.randomUUID(),SERVER=UUID.randomUUID();
 public static final String SHA="a".repeat(40),IMAGE="registry.example/shipora/image@sha256:"+"b".repeat(64);
 public static Job job(String runtime,String trigger){
  var config=new Configuration(APP,ORG,UUID.randomUUID(),UUID.randomUUID(),runtime,runtime.equals("DOCKER")?SERVER:null,runtime.equals("KUBERNETES")?UUID.randomUUID():null,"Web","web","main","Dockerfile",".",3000,"/",3,true);
  return new Job(UUID.randomUUID(),UUID.randomUUID(),Instant.now().plusSeconds(60),1,new DeploymentInfo(UUID.randomUUID(),ORG,APP,SHA,"main",trigger,"PREPARING",config),17);
 }
 public static WorkerProperties properties(Path root){
  return properties(root,false);
 }
 public static WorkerProperties properties(Path root,boolean enabled){
  return new WorkerProperties(enabled,"http://localhost:8083","http://localhost:8082","http://localhost:8084","http://localhost:8081","worker","test-only",root,
    "unix:///run/buildkit/buildkitd.sock",List.of("buildctl"),null,"registry.example/shipora",Duration.ofSeconds(10),Duration.ofSeconds(2),1,List.of(),"","",null,null,null,false);
 }
}
