package com.shipora.pipeline;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
@Configuration
public class PipelineConfiguration {
 @Bean PipelineHttpAdvice pipelineHttpAdvice(){return new PipelineHttpAdvice();}
 @Bean(destroyMethod="close") InternalHttp pipelineHttp(){return new InternalHttp();}
 @Bean LeaseAuthorizer leaseAuthorizer(InternalHttp http,Environment env){return new LeaseAuthorizer(http,env.getProperty("DEPLOYMENT_SERVICE_URL","http://localhost:8083"));}
}
