package com.shipora.worker.application;
import com.shipora.worker.application.port.*;
import com.shipora.worker.domain.ExecutionFailure;
import com.shipora.pipeline.Protocol.Job;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
public final class LeaseMonitor implements Guard,AutoCloseable {
 private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("worker-heartbeat").factory());
 private final AtomicBoolean lost=new AtomicBoolean();private volatile Instant expires;
 public LeaseMonitor(DeploymentGateway gateway,Job job){
  expires=job.leaseExpiresAt();
  scheduler.scheduleWithFixedDelay(()->{try{expires=gateway.heartbeat(job);}catch(RuntimeException e){lost.set(true);}},0,20,TimeUnit.SECONDS);
 }
 public void check(){if(Thread.currentThread().isInterrupted()||lost.get()||!Instant.now().isBefore(expires.minusSeconds(2)))throw new ExecutionFailure.LeaseLost();}
 public void close(){scheduler.shutdownNow();}
}
