package com.shipora.worker.configuration;

import com.shipora.worker.application.port.CommandExecutor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

@lombok.extern.slf4j.Slf4j
public final class WorkerStartup implements SmartInitializingSingleton {
 private final WorkerProperties properties;private final CommandExecutor commands;
 public WorkerStartup(WorkerProperties properties,CommandExecutor commands){this.properties=properties;this.commands=commands;}
 @Override public void afterSingletonsInstantiated(){
  if(!properties.enabled()){
   log.warn("Deployment worker is disabled. Jobs remain QUEUED. Configure BuildKit, registry access and worker credentials before setting SHIPORA_WORKER_ENABLED=true.");return;
  }
  try{
   var buildctl=new ArrayList<>(properties.buildctlCommand());buildctl.add("--version");
   commands.run(buildctl,Path.of(".").toAbsolutePath(),Map.of(),Duration.ofSeconds(5),()->{},ignored->{},false);
   commands.run(List.of("git","--version"),Path.of(".").toAbsolutePath(),Map.of(),Duration.ofSeconds(5),()->{},ignored->{},false);
  }catch(RuntimeException failure){throw new IllegalStateException("Worker requires executable buildctl and git commands. Run the configured worker container or install these tools before enabling processing.");}
  log.info("Deployment worker prerequisites found; polling for jobs every two seconds.");
 }
}
