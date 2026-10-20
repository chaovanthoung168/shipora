package com.shipora.worker.adapter.out.buildkit;

import com.shipora.worker.application.ProcessLogSanitizer;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildOutputTest {
 final JsonMapper json=JsonMapper.builder().build();
 @Test void joinsChunksAndUtf8BeforeRedactionAndFlushesTheFinalLine(){
  var sanitizer=new ProcessLogSanitizer();sanitizer.hide(List.of("private-token"));var lines=new ArrayList<String>();
  var decoder=new BuildOutput(line->lines.add(sanitizer.clean(line)));
  chunk(decoder,"npm running private-");assertTrue(lines.isEmpty());
  chunk(decoder,"token\nBuild complete");assertEquals(List.of("npm running [redacted]"),lines);
  decoder.finish();assertEquals(List.of("npm running [redacted]","Build complete"),lines);
 }
 @Test void largeLinesAreDroppedInsteadOfLeakingPartialSecrets(){
  var lines=new ArrayList<String>();var decoder=new BuildOutput(lines::add);
  chunk(decoder,"x".repeat(9000));chunk(decoder,"private-token\nready\n");
  assertEquals(List.of("[oversized build output line omitted]","ready"),lines);
 }
 @Test void reportsActualStepStartAndCompletionOnceEvenWhenProgressRepeats(){
  var lines=new ArrayList<String>();var decoder=new BuildOutput(lines::add);
  vertex(decoder,Map.of("digest","step","name","RUN npm ci"));
  assertEquals(List.of("Build step: RUN npm ci"),lines);
  var started=Map.of("digest","step","started","2026-10-20T09:00:00Z");vertex(decoder,started);vertex(decoder,started);
  var completed=Map.of("digest","step","started","2026-10-20T09:00:00Z","completed","2026-10-20T09:01:00Z");vertex(decoder,completed);vertex(decoder,completed);
  assertEquals(List.of("Build step: RUN npm ci","Started build step: RUN npm ci","Completed build step: RUN npm ci"),lines);
 }
 @Test void distinguishesCachedAndFailedStepsWithoutReportingFailureAsSuccess(){
  var lines=new ArrayList<String>();var decoder=new BuildOutput(lines::add);
  vertex(decoder,Map.of("digest","cached","name","COPY dependencies","completed","2026-10-20T09:00:00Z","cached",true));
  vertex(decoder,Map.of("digest","failed","name","RUN npm run build","completed","2026-10-20T09:01:00Z","error","build exited 1"));
  assertTrue(lines.contains("Cached build step: COPY dependencies"));assertTrue(lines.contains("Failed build step: RUN npm run build"));
  assertFalse(lines.contains("Completed build step: RUN npm run build"));
 }
 void vertex(BuildOutput decoder,Map<String,?> vertex){decoder.accept(json.valueToTree(Map.of("vertexes",List.of(vertex))));}
 void chunk(BuildOutput decoder,String line){decoder.accept(json.valueToTree(Map.of("logs",List.of(Map.of("vertex","step","stream",1,"data",Base64.getEncoder().encodeToString(line.getBytes(StandardCharsets.UTF_8)))))));}
}
