package com.shipora.worker.application.port;
import com.shipora.pipeline.Protocol.ServerContext;
import java.util.Map;
public interface RemoteDocker {Map<String,Object> execute(ServerContext server,Map<String,Object> request,Guard guard);}
