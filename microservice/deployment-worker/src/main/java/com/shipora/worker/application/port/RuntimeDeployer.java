package com.shipora.worker.application.port;
import com.shipora.pipeline.Protocol.*;
public interface RuntimeDeployer {
 boolean supports(String runtime);
 Handle deploy(Job job,ApplicationContext context,ServerContext server,String image,Guard guard);
 String verify(Handle handle,Guard guard);
 void rollback(Handle handle,Guard guard);
 interface Handle {}
}
