package com.shipora.worker.application.port;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
public interface CommandExecutor {
 String run(List<String> command,Path directory,Map<String,String> environment,Duration timeout,Guard guard,Consumer<String> lines,boolean capture);
}
