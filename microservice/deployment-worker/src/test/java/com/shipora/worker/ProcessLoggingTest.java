package com.shipora.worker;

import com.shipora.worker.application.*;
import com.shipora.worker.application.port.DeploymentGateway;
import com.shipora.worker.configuration.WorkerStartup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProcessLoggingTest {
 @TempDir Path directory;
 @Test void secretsAuthorizationUrlsAndPrivateKeysAreRedactedBeforeStorage(){
  var sanitizer=new ProcessLogSanitizer();sanitizer.hide(List.of("job-secret-value"));
  for(String line:List.of("value job-secret-value", "TOKEN=private-value", "Authorization: Bearer private-value", "https://user:private-value@example.test/path")){
   String safe=sanitizer.clean(line);assertFalse(safe.contains("job-secret-value"));assertFalse(safe.contains("private-value"));assertTrue(safe.contains("[redacted]"));
  }
  assertFalse(sanitizer.clean("-----BEGIN PRIVATE KEY-----").contains("BEGIN"));
  assertFalse(sanitizer.clean("private-key-body").contains("private-key-body"));
  sanitizer.clean("-----END PRIVATE KEY-----");assertEquals("ready",sanitizer.clean("ready"));
  assertTrue(sanitizer.clean("x".repeat(5000)).length()<=2048);
 }
 @Test void loggerIsBoundedAndLogOutagesDoNotFailExecution(){
  var gateway=mock(DeploymentGateway.class);var events=new ArrayList<DeploymentGateway.ProcessEvent>();
  doAnswer(call->{events.addAll(call.getArgument(1));return null;}).when(gateway).events(any(),any());
  try(var reporter=new JobEventReporter(gateway,Fixtures.job("DOCKER","MANUAL"))){
   reporter.hide(List.of("private-value"));reporter.accept("password=private-value");reporter.flush();
   for(int i=0;i<3000;i++)reporter.accept("build output "+i);
   reporter.flush();reporter.flush();
  }
  assertTrue(events.size()<100);assertEquals(1,events.stream().filter(e->e.level().equals("WARN")).count());
  assertTrue(events.stream().noneMatch(e->e.message().contains("private-value")));
  doThrow(new RuntimeException("storage unavailable")).when(gateway).events(any(),any());
  assertDoesNotThrow(()->{try(var reporter=new JobEventReporter(gateway,Fixtures.job("DOCKER","MANUAL"))){reporter.accept("building");reporter.flush();}});
 }
 @Test void startupChecksOnlyEnabledWorkersAndReportsMissingToolsWithoutPrivateArguments(){
  var calls=new ArrayList<List<String>>();
  new WorkerStartup(Fixtures.properties(directory),(a,b,c,d,e,f,g)->{calls.add(a);return "";}).afterSingletonsInstantiated();
  assertTrue(calls.isEmpty());
  var properties=Fixtures.properties(directory,true);
  new WorkerStartup(properties,(a,b,c,d,e,f,g)->{calls.add(a);return "";}).afterSingletonsInstantiated();
  assertEquals(List.of(List.of("buildctl","--version"),List.of("git","--version")),calls);
  var failure=assertThrows(IllegalStateException.class,()->new WorkerStartup(properties,(a,b,c,d,e,f,g)->{throw new RuntimeException("private-value");}).afterSingletonsInstantiated());
  assertFalse(failure.toString().contains("private-value"));
 }
}
