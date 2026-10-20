package com.shipora.worker.application;

import com.shipora.pipeline.Protocol.Job;
import com.shipora.worker.application.port.DeploymentGateway;
import com.shipora.worker.application.port.DeploymentGateway.ProcessEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Logging is bounded and best effort; unavailable log storage cannot block a build. */
public final class JobEventReporter implements Consumer<String>,AutoCloseable {
 private final DeploymentGateway gateway;private final Job job;
 private final ProcessLogSanitizer sanitizer=new ProcessLogSanitizer();
 private final ArrayBlockingQueue<ProcessEvent> pending=new ArrayBlockingQueue<>(50);
 private final AtomicInteger remaining=new AtomicInteger(2000);private final AtomicBoolean omitted=new AtomicBoolean();private boolean warned;
 private final ScheduledExecutorService sender=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("worker-events").factory());
 public JobEventReporter(DeploymentGateway gateway,Job job){
  this.gateway=gateway;this.job=job;sanitizer.hide(List.of(job.leaseToken().toString()));
  sender.scheduleWithFixedDelay(this::flush,500,500,TimeUnit.MILLISECONDS);
 }
 public void hide(Collection<String> values){sanitizer.hide(values);}
 @Override public void accept(String line){message("INFO",line);}
 public void message(String level,String line){
  String safe=sanitizer.clean(line);if(safe.isBlank())return;
  if(remaining.getAndDecrement()<=0||!pending.offer(new ProcessEvent(level,safe)))omitted.set(true);
 }
 public synchronized void flush(){
  var batch=new ArrayList<ProcessEvent>();pending.drainTo(batch,49);
  if(omitted.get()&&!warned){warned=true;batch.add(new ProcessEvent("WARN","Some process output was omitted because the logging buffer or output limit was reached."));}
  if(batch.isEmpty())return;
  try{gateway.events(job,List.copyOf(batch));}catch(RuntimeException ignored){/* A failed log request must not stop deployment or lease renewal. */}
 }
 @Override public void close(){sender.shutdownNow();flush();flush();}
}
