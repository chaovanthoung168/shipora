package com.shipora.git.configuration;
import com.shipora.git.adapter.out.persistence.PushOutboxRelay;
import com.shipora.pipeline.InternalHttp;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration @EnableScheduling
@ConditionalOnProperty(name="shipora.pipeline.automatic-push-enabled",havingValue="true")
public class AutomaticPushConfiguration {
 @Bean PushOutboxRelay pushOutboxRelay(JdbcTemplate jdbc,PlatformTransactionManager manager,InternalHttp http,Environment environment){return new PushOutboxRelay(jdbc,manager,http,environment);}
}
