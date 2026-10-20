package com.shipora.worker.adapter.out.buildkit;

import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Reassemble log chunks before redaction so a token split across chunks cannot escape. */
final class BuildOutput {
 private final Consumer<String> output;
 private final Map<String,Line> streams=new HashMap<>();private final Map<String,Step> vertices=new HashMap<>();
 BuildOutput(Consumer<String> output){this.output=output;}
 synchronized void accept(JsonNode event){
  if(event.path("vertexes").isArray())for(var vertex:event.path("vertexes")){
   String id=vertex.path("digest").asText("");
   Step step=vertices.get(id);
   if(step==null&&!id.isEmpty()&&vertices.size()<500){step=new Step(vertex.path("name").asText("Processing image"));vertices.put(id,step);output.accept("Build step: "+step.name);}
   if(step!=null){
    if(vertex.hasNonNull("started")&&!step.started){step.started=true;output.accept("Started build step: "+step.name);}
    if(vertex.hasNonNull("completed")&&!step.completed){
     step.completed=true;output.accept((vertex.hasNonNull("error")?"Failed build step: ":vertex.path("cached").asBoolean(false)?"Cached build step: ":"Completed build step: ")+step.name);
    }
   }
   if(vertex.hasNonNull("error"))output.accept("Build error: "+vertex.path("error").asText("Build step failed"));
  }
  if(event.path("logs").isArray())for(var log:event.path("logs")){
   String key=log.path("vertex").asText("")+":"+log.path("stream").asInt();
   if(key.length()>256||(!streams.containsKey(key)&&streams.size()>=64))continue;
   var line=streams.computeIfAbsent(key,ignored->new Line());
   byte[] data;
   try{data=Base64.getDecoder().decode(log.path("data").asText(""));}catch(IllegalArgumentException ignored){continue;}
   for(byte value:data){
    if(value=='\n'){
     if(!line.oversized)output.accept(line.bytes.toString(StandardCharsets.UTF_8));
     else output.accept("[oversized build output line omitted]");
     line.bytes.reset();line.oversized=false;
    }else if(!line.oversized&&line.bytes.size()<8192)line.bytes.write(value);
    else{line.bytes.reset();line.oversized=true;}
   }
  }
 }
 synchronized void finish(){for(var line:streams.values())if(!line.oversized&&line.bytes.size()>0)output.accept(line.bytes.toString(StandardCharsets.UTF_8));streams.clear();}
 private static final class Step {final String name;boolean started,completed;Step(String name){this.name=name;}}
 private static final class Line {final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();boolean oversized;}
}
