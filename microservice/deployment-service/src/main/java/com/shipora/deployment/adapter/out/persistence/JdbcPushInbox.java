package com.shipora.deployment.adapter.out.persistence;
import com.shipora.deployment.application.port.out.PushInboxStore;
import com.shipora.deployment.domain.DeploymentRules;
import com.shipora.pipeline.Protocol.Push;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
@Repository
public class JdbcPushInbox implements PushInboxStore {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final JsonMapper json;
 public JdbcPushInbox(JdbcTemplate jdbc,PlatformTransactionManager manager,JsonMapper json){this.jdbc=jdbc;tx=new TransactionTemplate(manager);this.json=json;}
 @Override public void accept(UUID org,Push push){
  tx.executeWithoutResult(status->{
   jdbc.update("INSERT INTO deployment.push_inbox(organization_id,event_id,repository_id,commit_sha,branch) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",org,push.eventId(),push.repositoryId(),push.commitSha(),push.branch());
   var original=jdbc.queryForMap("SELECT repository_id,commit_sha,branch FROM deployment.push_inbox WHERE organization_id=? AND event_id=?",org,push.eventId());
   if(!push.repositoryId().equals(original.get("repository_id")) || !push.commitSha().equals(original.get("commit_sha")) || !push.branch().equals(original.get("branch")))throw DeploymentRules.conflict("Push event ID was already used with a different payload.");
  });
 }
 @Override public Optional<Pending> claim(UUID org){
  return tx.execute(status->{
   var rows=jdbc.query("SELECT * FROM deployment.push_inbox WHERE organization_id=? AND completed_at IS NULL AND next_attempt_at<=clock_timestamp() AND (leased_until IS NULL OR leased_until<=clock_timestamp()) ORDER BY created_at,event_id FOR UPDATE SKIP LOCKED LIMIT 1",
    (rs,n)->new Pending(org,new Push(rs.getObject("event_id",UUID.class),rs.getObject("repository_id",UUID.class),rs.getString("commit_sha"),rs.getString("branch")),UUID.randomUUID(),
       rs.getString("targets")==null?null:List.of(json.readValue(rs.getString("targets"),UUID[].class)),rs.getInt("cursor"),rs.getInt("attempts")),org);
   if(rows.isEmpty())return Optional.empty();var row=rows.getFirst();
   jdbc.update("UPDATE deployment.push_inbox SET lease_token=?,leased_until=clock_timestamp()+interval '180 seconds',attempts=attempts+1 WHERE organization_id=? AND event_id=?",row.token(),org,row.push().eventId());
   return Optional.of(row);
  });
 }
 @Override public void targets(Pending p,List<UUID> targets){
  jdbc.update("UPDATE deployment.push_inbox SET targets=CAST(? AS jsonb),completed_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,lease_token=NULL,leased_until=NULL,last_code=NULL WHERE organization_id=? AND event_id=? AND lease_token=?",
    json.writeValueAsString(targets),targets.isEmpty(),p.organizationId(),p.push().eventId(),p.token());
 }
 @Override public void advance(Pending p){
  jdbc.update("UPDATE deployment.push_inbox SET cursor=cursor+1,completed_at=CASE WHEN cursor+1>=jsonb_array_length(targets) THEN clock_timestamp() ELSE NULL END,attempts=0,lease_token=NULL,leased_until=NULL,last_code=NULL WHERE organization_id=? AND event_id=? AND lease_token=?",
    p.organizationId(),p.push().eventId(),p.token());
 }
 @Override public void postpone(Pending p){
  jdbc.update("UPDATE deployment.push_inbox SET next_attempt_at=clock_timestamp()+make_interval(secs=>?),lease_token=NULL,leased_until=NULL,last_code='DISPATCH_UNAVAILABLE' WHERE organization_id=? AND event_id=? AND lease_token=?",
    Math.min(300,1<<Math.min(p.attempts()+1,8)),p.organizationId(),p.push().eventId(),p.token());
 }
}
