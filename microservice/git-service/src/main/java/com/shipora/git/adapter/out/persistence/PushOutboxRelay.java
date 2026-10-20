package com.shipora.git.adapter.out.persistence;
import com.shipora.pipeline.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import java.net.URI;
import java.util.*;
public final class PushOutboxRelay {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final InternalHttp http;private final URI deployment;private final UUID org;private final String authorization;
 public PushOutboxRelay(JdbcTemplate jdbc,PlatformTransactionManager manager,InternalHttp http,Environment env){
  this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.http=http;deployment=InternalHttp.origin(env.getProperty("DEPLOYMENT_SERVICE_URL","http://localhost:8083"));
  org=UUID.fromString(env.getProperty("shipora.local.organization-id","11111111-1111-1111-1111-111111111111"));authorization=PipelineAccounts.automationAuth(env);
 }
 @Scheduled(fixedDelay=2000) public void deliver(){
  Row row;
  try{row=tx.execute(status->{
   var due=jdbc.query("SELECT id,repository_id,commit_sha,branch,attempts FROM git.deployment_push_outbox WHERE organization_id=? AND delivered_at IS NULL AND next_attempt_at<=clock_timestamp() AND (leased_until IS NULL OR leased_until<=clock_timestamp()) ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1",
    (rs,n)->new Row(rs.getObject("id",UUID.class),rs.getObject("repository_id",UUID.class),rs.getString("commit_sha"),rs.getString("branch"),rs.getInt("attempts"),UUID.randomUUID()),org);
   if(due.isEmpty())return null;var selected=due.getFirst();
   jdbc.update("UPDATE git.deployment_push_outbox SET lease_token=?,leased_until=clock_timestamp()+interval '30 seconds',attempts=attempts+1 WHERE id=?",selected.token(),selected.id());return selected;
  });}catch(RuntimeException e){return;}
  if(row==null)return;
  try{
   http.send(deployment,"/internal/git-pushes","POST",authorization,new Protocol.Push(row.id(),row.repository(),row.sha(),row.branch()));
   jdbc.update("UPDATE git.deployment_push_outbox SET delivered_at=clock_timestamp(),lease_token=NULL,leased_until=NULL,last_code=NULL WHERE id=? AND lease_token=?",row.id(),row.token());
  }catch(RuntimeException e){
   int delay=Math.min(300,1<<Math.min(row.attempts()+1,8));
   jdbc.update("UPDATE git.deployment_push_outbox SET next_attempt_at=clock_timestamp()+make_interval(secs=>?),lease_token=NULL,leased_until=NULL,last_code='DELIVERY_UNAVAILABLE' WHERE id=? AND lease_token=?",delay,row.id(),row.token());
  }
 }
 private record Row(UUID id,UUID repository,String sha,String branch,int attempts,UUID token){}
}
