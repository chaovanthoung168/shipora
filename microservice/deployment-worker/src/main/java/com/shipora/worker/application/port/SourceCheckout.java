package com.shipora.worker.application.port;
import com.shipora.pipeline.Protocol.*;
import java.nio.file.Path;
public interface SourceCheckout {Path checkout(Job job,SourceContext source,Path directory,Guard guard);}
