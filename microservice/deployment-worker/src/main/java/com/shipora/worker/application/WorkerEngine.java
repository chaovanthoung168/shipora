package com.shipora.worker.application;
import com.shipora.pipeline.Protocol.*;
import com.shipora.worker.application.port.*;
import com.shipora.worker.domain.ExecutionFailure;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
public final class WorkerEngine {
 private final DeploymentGateway gateway;private final Contexts contexts;private final SourceCheckout checkout;private final ImageBuilder builder;private final List<RuntimeDeployer> runtimes;
 public WorkerEngine(DeploymentGateway gateway,Contexts contexts,SourceCheckout checkout,ImageBuilder builder,List<RuntimeDeployer> runtimes){
  this.gateway=gateway;this.contexts=contexts;this.checkout=checkout;this.builder=builder;this.runtimes=List.copyOf(runtimes);
 }
 public void execute(Job job,Path workspace){
  var stage=new AtomicReference<>("PREPARING");RuntimeDeployer runtime=null;RuntimeDeployer.Handle handle=null;
  try(var lease=new LeaseMonitor(gateway,job);var logs=new JobEventReporter(gateway,job)){
   Guard guard=()->{lease.check();};
   try{
    if(job.fence()<1)throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");
    logs.accept("Fetching application configuration.");
    var app=contexts.application(job);logs.hide(app.environment().values());if(app.version()!=job.deployment().configuration().version())throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");
    logs.accept("Requesting access to the exact source revision.");
    var source=contexts.source(job);if(source.token()!=null)logs.hide(List.of(source.token(),"x-access-token:"+source.token()));checkHead(job,source);
    var server="DOCKER".equals(job.deployment().configuration().runtimeType())?contexts.server(job):null;
    runtime=runtimes.stream().filter(r->r.supports(job.deployment().configuration().runtimeType())).findFirst().orElseThrow(()->new ExecutionFailure("CONFIGURATION_UNAVAILABLE"));
    logs.accept("Checking out commit "+job.deployment().commitSha()+".");
    Path repository=checkout.checkout(job,source,workspace,guard);guard.check();logs.accept("Exact-commit checkout verified; temporary Git authentication removed.");
    gateway.progress(job,"PREPARING","BUILDING",null,null,null);stage.set("BUILDING");
    Runnable pushing=()->{synchronized(stage){if(stage.get().equals("BUILDING")){gateway.progress(job,"BUILDING","PUSHING",null,null,null);stage.set("PUSHING");}}};
    logs.accept("Starting BuildKit image build and registry publication.");
    String image=builder.buildAndPush(job,repository,guard,pushing,logs);pushing.run();guard.check();logs.accept("Image published with an immutable digest.");
    // Do not deploy an older push after a newer commit reached the tracked branch.
    checkHead(job,contexts.source(job));app=contexts.application(job);gateway.authorize(job);
    gateway.progress(job,"PUSHING","DEPLOYING",image,null,null);stage.set("DEPLOYING");
    logs.accept("Rolling out the image to the configured runtime target.");
    handle=runtime.deploy(job,app,server,image,guard);guard.check();
    gateway.progress(job,"DEPLOYING","VERIFYING",null,null,null);stage.set("VERIFYING");
    logs.accept("Waiting for runtime health verification.");
    String endpoint=runtime.verify(handle,guard);guard.check();gateway.authorize(job);
    logs.accept("Runtime health verified.");logs.flush();logs.flush();
    gateway.progress(job,"VERIFYING","SUCCEEDED",null,null,endpoint);stage.set("SUCCEEDED");
   }catch(ExecutionFailure.LeaseLost e){/* A reassigned/expired worker must never report or mutate the runtime. */}
   catch(RuntimeException e){
    try{
     guard.check();gateway.authorize(job);
     if(runtime!=null&&handle!=null){
      try{logs.message("WARN","Runtime verification or rollout failed; attempting rollback.");runtime.rollback(handle,guard);}catch(ExecutionFailure.LeaseLost lost){throw lost;}
      catch(RuntimeException rollbackFailure){guard.check();gateway.authorize(job);}
     }
     String code=e instanceof ExecutionFailure failure?failure.code:switch(stage.get()){case "PREPARING"->"CONFIGURATION_UNAVAILABLE";case "BUILDING"->"BUILD_FAILED";case "PUSHING"->"PUSH_FAILED";case "DEPLOYING"->"DEPLOY_FAILED";default->"HEALTH_CHECK_FAILED";};
     if(e instanceof ExecutionFailure.RuntimeRejected rejected)logs.message("ERROR",rejected.detail);
     logs.message("ERROR","Deployment stopped: "+code+".");logs.flush();logs.flush();
     gateway.progress(job,stage.get(),"FAILED",null,code,null);
    }catch(RuntimeException ignored){/* Lease loss/outage is recovered by the durable queue. */}
   }
  }
 }
 private void checkHead(Job job,SourceContext source){
  if("GIT_PUSH".equals(job.deployment().triggerType())&&!job.deployment().commitSha().equals(source.branchHead()))throw new ExecutionFailure("SUPERSEDED");
 }
}
