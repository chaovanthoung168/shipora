package com.shipora.pipeline;
import java.net.URI;
import java.util.UUID;
public final class LeaseAuthorizer {
 private final InternalHttp http;private final URI deployment;
 public LeaseAuthorizer(InternalHttp http,String origin){this.http=http;deployment=InternalHttp.origin(origin);}
 public Protocol.Job authorize(UUID org,UUID job,UUID token,String authorization){
  if(job==null || token==null || authorization==null)throw new InternalHttp.RemoteFailure(403);
  var result=http.json.treeToValue(http.send(deployment,"/internal/deployment-jobs/"+job+"/authorizations","POST",authorization,new Protocol.LeaseRequest(token)),Protocol.Job.class);
  if(result==null || !job.equals(result.jobId()) || !token.equals(result.leaseToken()) || result.fence()<1
   || result.deployment()==null || !org.equals(result.deployment().organizationId()))throw new InternalHttp.RemoteFailure(403);
  return result;
 }
}
