package com.shipora.worker;
import com.shipora.pipeline.Protocol.*;
import com.shipora.worker.application.WorkerEngine;
import com.shipora.worker.application.port.*;
import com.shipora.worker.domain.ExecutionFailure;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class WorkerEngineTest {
 @TempDir Path directory;
 final DeploymentGateway gateway=mock(DeploymentGateway.class);final Contexts contexts=mock(Contexts.class);final SourceCheckout checkout=mock(SourceCheckout.class);
 final ImageBuilder builder=mock(ImageBuilder.class);final RuntimeDeployer runtime=mock(RuntimeDeployer.class);final RuntimeDeployer.Handle handle=new RuntimeDeployer.Handle(){};
 final WorkerEngine engine=new WorkerEngine(gateway,contexts,checkout,builder,List.of(runtime));
 Job job=Fixtures.job("DOCKER","MANUAL");
 @BeforeEach void setup(){
  when(gateway.heartbeat(any())).thenReturn(Instant.now().plusSeconds(60));
  when(contexts.application(any())).thenReturn(new ApplicationContext(3,Map.of("TOKEN","private-value"),null));
  when(contexts.source(any())).thenReturn(new SourceContext("https://github.com/example/web.git","test-token",Instant.now().plusSeconds(3600),Fixtures.SHA));
  when(contexts.server(any())).thenReturn(new ServerContext(Fixtures.ORG,Fixtures.SERVER,"127.0.0.1",22,"root","test-key","SHA256:dummy"));
  when(checkout.checkout(any(),any(),any(),any())).thenReturn(directory);
  when(runtime.supports("DOCKER")).thenReturn(true);when(runtime.deploy(any(),any(),any(),any(),any())).thenReturn(handle);when(runtime.verify(eq(handle),any())).thenReturn("http://127.0.0.1:3000");
  when(builder.buildAndPush(any(),any(),any(),any(),any())).thenAnswer(call->{((Runnable)call.getArgument(3)).run();return Fixtures.IMAGE;});
 }
 List<String> stages(){return mockingDetails(gateway).getInvocations().stream().filter(i->i.getMethod().getName().equals("progress")).map(i->(String)i.getArgument(2)).toList();}
 @Test void executesAllStagesAndOnlySucceedsAfterRuntimeVerification(){
  engine.execute(job,directory);assertEquals(List.of("BUILDING","PUSHING","DEPLOYING","VERIFYING","SUCCEEDED"),stages());
  verify(runtime).verify(eq(handle),any());verify(runtime,never()).rollback(any(),any());verify(gateway).progress(job,"VERIFYING","SUCCEEDED",null,null,"http://127.0.0.1:3000");
 }
 @Test void buildOutputIsRedactedBeforeItReachesTheDeploymentGateway(){
  doAnswer(call->{
   ((java.util.function.Consumer<String>)call.getArgument(4)).accept("Build output test-token private-value TOKEN=private-value");
   ((Runnable)call.getArgument(3)).run();return Fixtures.IMAGE;
  }).when(builder).buildAndPush(any(),any(),any(),any(),any());
  engine.execute(job,directory);
  var batches=org.mockito.ArgumentCaptor.forClass(List.class);verify(gateway,atLeastOnce()).events(eq(job),batches.capture());
  var messages=new ArrayList<String>();for(var batch:batches.getAllValues())for(var entry:batch)messages.add(((DeploymentGateway.ProcessEvent)entry).message());
  String output=String.join("\n",messages);
  assertTrue(output.contains("Build output"));assertFalse(output.contains("test-token"));assertFalse(output.contains("private-value"));
 }
 @Test void healthFailureRollsBackBeforeRecordingFailure(){
  when(runtime.verify(eq(handle),any())).thenThrow(new ExecutionFailure("HEALTH_CHECK_FAILED"));engine.execute(job,directory);
  var ordered=inOrder(runtime,gateway);ordered.verify(runtime).verify(eq(handle),any());ordered.verify(runtime).rollback(eq(handle),any());ordered.verify(gateway).progress(job,"VERIFYING","FAILED",null,"HEALTH_CHECK_FAILED",null);
 }
 @Test void runtimeFailureAddsAnActionableSafeEventWithoutExposingRemoteErrorText(){
  when(runtime.deploy(any(),any(),any(),any(),any())).thenThrow(new ExecutionFailure.RuntimeRejected("PULL_FAILED"));
  engine.execute(job,directory);
  var batches=org.mockito.ArgumentCaptor.forClass(List.class);verify(gateway,atLeastOnce()).events(eq(job),batches.capture());
  var messages=new ArrayList<String>();for(var batch:batches.getAllValues())for(var entry:batch)messages.add(((DeploymentGateway.ProcessEvent)entry).message());
  assertTrue(messages.contains("Docker could not pull the published image on the target server."));
  assertEquals("The remote Docker runtime could not complete the operation.",new ExecutionFailure.RuntimeRejected("password=private-value").detail);
  verify(gateway).progress(job,"DEPLOYING","FAILED",null,"DEPLOY_FAILED",null);
 }
 @Test void expiredLeaseStopsWorkWithoutRuntimeMutationOrFalseFailureReport(){
  doThrow(new ExecutionFailure.LeaseLost()).when(builder).buildAndPush(any(),any(),any(),any(),any());engine.execute(job,directory);
  assertEquals(List.of("BUILDING"),stages());verify(runtime,never()).deploy(any(),any(),any(),any(),any());verify(runtime,never()).rollback(any(),any());
 }
 @Test void changedConfigurationFailsBeforeCheckoutAndBuild(){
  when(contexts.application(any())).thenReturn(new ApplicationContext(4,Map.of(),null));engine.execute(job,directory);
  verify(gateway).progress(job,"PREPARING","FAILED",null,"CONFIGURATION_UNAVAILABLE",null);verifyNoInteractions(checkout,builder);
 }
 @Test void olderQueuedPushIsSupersededBeforeBuild(){
  job=Fixtures.job("DOCKER","GIT_PUSH");when(contexts.source(any())).thenReturn(new SourceContext("https://github.com/example/web.git","test",Instant.now().plusSeconds(3600),"c".repeat(40)));
  engine.execute(job,directory);verify(gateway).progress(job,"PREPARING","FAILED",null,"SUPERSEDED",null);verifyNoInteractions(checkout,builder);
 }
 @Test void pushThatBecomesOldDuringBuildCannotReachRuntime(){
  job=Fixtures.job("DOCKER","GIT_PUSH");when(contexts.source(any())).thenReturn(new SourceContext("https://github.com/example/web.git","test",Instant.now().plusSeconds(3600),Fixtures.SHA),new SourceContext("https://github.com/example/web.git","test",Instant.now().plusSeconds(3600),"c".repeat(40)));
  engine.execute(job,directory);verify(gateway).progress(job,"PUSHING","FAILED",null,"SUPERSEDED",null);verify(runtime,never()).deploy(any(),any(),any(),any(),any());
 }
}
