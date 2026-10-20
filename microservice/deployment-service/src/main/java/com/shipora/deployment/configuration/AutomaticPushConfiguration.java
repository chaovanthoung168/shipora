package com.shipora.deployment.configuration;
import com.shipora.deployment.application.port.out.*;
import com.shipora.deployment.application.service.AutomaticPushService;
import com.shipora.deployment.adapter.out.http.HttpAutomaticCatalog;
import com.shipora.pipeline.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.*;
import java.util.*;
@Configuration @EnableScheduling
public class AutomaticPushConfiguration {
 @Bean(destroyMethod="close") HttpAutomaticCatalog automaticCatalog(Environment environment,InternalHttp http,CatalogProperties properties){
  UUID org=UUID.fromString(environment.getProperty("shipora.local.organization-id","11111111-1111-1111-1111-111111111111"));
  // No network work is performed when delivery is disabled. Credentials are required when enabled.
  String auth=environment.getProperty("shipora.pipeline.automatic-push-enabled",Boolean.class,false)?PipelineAccounts.automationAuth(environment):"";
  return new HttpAutomaticCatalog(http,properties,auth,org);
 }
 @Bean AutomaticPushService automaticPushService(PushInboxStore inbox,DeploymentStore store,HttpAutomaticCatalog catalog){return new AutomaticPushService(inbox,store,catalog);}
 @Bean @ConditionalOnProperty(name="shipora.pipeline.automatic-push-enabled",havingValue="true")
 PushPoller pushPoller(AutomaticPushService service,Environment env){return new PushPoller(service,UUID.fromString(env.getProperty("shipora.local.organization-id","11111111-1111-1111-1111-111111111111")));}
 public record PushPoller(AutomaticPushService service,UUID org){
  @Scheduled(fixedDelay=2000) public void poll(){try{service.dispatch(org);}catch(RuntimeException ignored){/* Durable inbox is retried on the next poll. */}}
 }
}
