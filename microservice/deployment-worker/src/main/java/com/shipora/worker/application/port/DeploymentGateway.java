package com.shipora.worker.application.port;
import com.shipora.pipeline.Protocol.Job;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
public interface DeploymentGateway {
 Optional<Job> claim();
 Instant heartbeat(Job job);
 void authorize(Job job);
 void progress(Job job,String expected,String status,String image,String failure,String endpoint);
 void events(Job job,List<ProcessEvent> events);
 record ProcessEvent(String level,String message){@Override public String toString(){return "ProcessEvent[redacted]";}}
}
