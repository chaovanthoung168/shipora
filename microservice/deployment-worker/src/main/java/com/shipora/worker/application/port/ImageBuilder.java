package com.shipora.worker.application.port;
import com.shipora.pipeline.Protocol.Job;
import java.nio.file.Path;
import java.util.function.Consumer;
public interface ImageBuilder {
 String buildAndPush(Job job,Path repository,Guard guard,Runnable pushing);
 default String buildAndPush(Job job,Path repository,Guard guard,Runnable pushing,Consumer<String> output){return buildAndPush(job,repository,guard,pushing);}
}
