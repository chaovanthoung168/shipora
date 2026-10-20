package com.shipora.worker.adapter.out.http;
import com.shipora.pipeline.*;
import com.shipora.pipeline.Protocol.*;
import com.shipora.worker.application.port.*;
import com.shipora.worker.configuration.WorkerProperties;
import com.shipora.worker.domain.ExecutionFailure;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
public final class HttpWorkerGateway implements DeploymentGateway,Contexts {
 private final InternalHttp http;private final WorkerProperties properties;private final String authorization;
 public HttpWorkerGateway(InternalHttp http,WorkerProperties properties){this.http=http;this.properties=properties;authorization=PipelineAccounts.basic(properties.username(),properties.password());}
 public Optional<Job> claim(){var result=http.send(InternalHttp.origin(properties.deploymentUrl()),"/internal/deployment-jobs/claims","POST",authorization,null);return result.isNull()?Optional.empty():Optional.of(http.json.treeToValue(result,Job.class));}
 public Instant heartbeat(Job job){return Instant.parse(post(properties.deploymentUrl(),"/internal/deployment-jobs/"+job.jobId()+"/heartbeats",job).path("leaseExpiresAt").asText());}
 public void authorize(Job job){try{post(properties.deploymentUrl(),"/internal/deployment-jobs/"+job.jobId()+"/authorizations",job);}catch(InternalHttp.RemoteFailure e){throw new ExecutionFailure.LeaseLost();}}
 public void events(Job job,List<ProcessEvent> events){
  var body=Map.of("leaseToken",job.leaseToken(),"events",events);
  var request=HttpRequest.newBuilder(URI.create(InternalHttp.origin(properties.deploymentUrl())+"/internal/deployment-jobs/"+job.jobId()+"/events"))
   .timeout(Duration.ofSeconds(2)).header("Authorization",authorization).header("X-Shipora-Request","true").header("Content-Type","application/json")
   .POST(HttpRequest.BodyPublishers.ofByteArray(http.json.writeValueAsBytes(body))).build();
  http.send(request);
 }
 private tools.jackson.databind.JsonNode post(String origin,String path,Job job){
  try{return http.send(InternalHttp.origin(origin),path,"POST",authorization,new LeaseRequest(job.leaseToken()));}
  catch(InternalHttp.RemoteFailure e){throw e;}
 }
 public ApplicationContext application(Job job){return http.json.treeToValue(post(properties.applicationUrl(),"/internal/worker/jobs/"+job.jobId()+"/application-context",job),ApplicationContext.class);}
 public SourceContext source(Job job){return http.json.treeToValue(post(properties.gitUrl(),"/internal/worker/jobs/"+job.jobId()+"/source-context",job),SourceContext.class);}
 public ServerContext server(Job job){return http.json.treeToValue(post(properties.projectUrl(),"/internal/worker/jobs/"+job.jobId()+"/server-context",job),ServerContext.class);}
 public void progress(Job job,String expected,String status,String image,String failure,String endpoint){
  var body=new LinkedHashMap<String,Object>();body.put("leaseToken",job.leaseToken());body.put("expectedStatus",expected);body.put("status",status);
  if(image!=null)body.put("imageReference",image);if(failure!=null)body.put("failureCode",failure);if(endpoint!=null)body.put("runtimeEndpoint",endpoint);
  for(int attempt=0;;attempt++){
   try{http.send(InternalHttp.origin(properties.deploymentUrl()),"/internal/deployment-jobs/"+job.jobId()+"/status","PUT",authorization,body);return;}
   catch(InternalHttp.RemoteFailure e){if(e.status==401||e.status==403||e.status==409)throw new ExecutionFailure.LeaseLost();if(attempt>=2||e.status<500)throw e;}
  }
 }
}
