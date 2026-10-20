package com.shipora.worker.adapter.in.scheduling;
import com.shipora.worker.application.WorkerEngine;
import com.shipora.worker.application.port.DeploymentGateway;
import com.shipora.worker.adapter.out.process.SafeFiles;
import com.shipora.worker.configuration.WorkerProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
@lombok.extern.slf4j.Slf4j
public final class WorkerSupervisor {
 private final DeploymentGateway gateway;private final WorkerEngine engine;private final WorkerProperties properties;
 private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();private final Semaphore slots;
 private final Map<UUID,FutureTask<Void>> active=new ConcurrentHashMap<>();private volatile boolean closing;private long lastWarning;
 public WorkerSupervisor(DeploymentGateway gateway,WorkerEngine engine,WorkerProperties properties){this.gateway=gateway;this.engine=engine;this.properties=properties;slots=new Semaphore(properties.concurrency());}
 @Scheduled(fixedDelay=2000) public void poll(){
  if(closing||!slots.tryAcquire())return;
  try{
   var claimed=gateway.claim();if(claimed.isEmpty()){slots.release();return;}var job=claimed.get();
   var task=new FutureTask<Void>(()->{
    Path directory=null,root=null;boolean created=false;
    try{root=SafeFiles.directory(properties.workspace()).toRealPath();directory=root.resolve(job.jobId()+"-"+job.fence());Files.createDirectory(directory);created=true;SafeFiles.privateMode(directory,true);engine.execute(job,directory);}
    catch(Exception ignored){try{gateway.progress(job,"PREPARING","FAILED",null,"CONFIGURATION_UNAVAILABLE",null);}catch(RuntimeException denied){}}
    finally{if(created&&directory!=null&&root!=null){try{SafeFiles.deleteWorkspace(root,directory);}catch(Exception ignored){}}slots.release();active.remove(job.jobId());}
    return null;
   });active.put(job.jobId(),task);executor.execute(task);
  }catch(RuntimeException e){
   slots.release();long now=System.nanoTime();
   if(lastWarning==0||now-lastWarning>TimeUnit.MINUTES.toNanos(1)){
    lastWarning=now;log.warn("Deployment claim unavailable. Check service reachability and worker credentials.");
   }
  }
 }
 @PreDestroy public void close(){closing=true;active.values().forEach(task->task.cancel(true));executor.shutdownNow();}
}
