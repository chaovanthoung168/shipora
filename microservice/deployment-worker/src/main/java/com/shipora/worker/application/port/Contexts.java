package com.shipora.worker.application.port;
import com.shipora.pipeline.Protocol.*;
public interface Contexts {
 ApplicationContext application(Job job);
 SourceContext source(Job job);
 ServerContext server(Job job);
}
