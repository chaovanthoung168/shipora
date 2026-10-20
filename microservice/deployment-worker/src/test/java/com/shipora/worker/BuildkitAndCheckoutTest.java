package com.shipora.worker;
import com.shipora.worker.adapter.out.buildkit.BuildkitImageBuilder;
import com.shipora.worker.adapter.out.git.GitCheckout;
import com.shipora.worker.adapter.out.process.SafeFiles;
import com.shipora.worker.application.port.*;
import com.shipora.worker.domain.ExecutionFailure;
import com.shipora.pipeline.Protocol.SourceContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class BuildkitAndCheckoutTest {
 @TempDir Path directory;
 @Test void buildkitUsesArgumentVectorPushesDigestAndNeverReceivesRuntimeSecrets()throws Exception{
  Path repo=Files.createDirectory(directory.resolve("repo"));Files.writeString(repo.resolve("Dockerfile"),"FROM scratch\n");
  var pushing=new AtomicInteger();var captured=new ArrayList<String>();
  CommandExecutor commands=(command,cwd,environment,timeout,guard,lines,capture)->{
   captured.addAll(command);assertFalse(environment.containsKey("TOKEN"));assertFalse(command.toString().contains("private-value"));assertTrue(command.contains("rawjson"));
   lines.accept("{\"vertexes\":[{\"name\":\"exporting to image\"}]}");
   try{Files.writeString(Path.of(command.get(command.indexOf("--metadata-file")+1)),"{\"containerimage.digest\":\"sha256:"+"b".repeat(64)+"\"}");}catch(Exception e){throw new RuntimeException(e);}
   return "";
  };
  String image=new BuildkitImageBuilder(Fixtures.properties(directory),commands).buildAndPush(Fixtures.job("DOCKER","MANUAL"),repo,()->{},pushing::incrementAndGet);
  assertTrue(image.endsWith("@sha256:"+"b".repeat(64)));assertTrue(captured.stream().anyMatch(s->s.contains("push=true")));assertTrue(pushing.get()>0);
 }
 @Test void buildkitRejectsMissingMetadataAndEscapingBuildPaths()throws Exception{
  Path repo=Files.createDirectory(directory.resolve("repo"));Files.writeString(repo.resolve("Dockerfile"),"FROM scratch\n");
  var builder=new BuildkitImageBuilder(Fixtures.properties(directory),(a,b,c,d,e,f,g)->"");
  assertThrows(ExecutionFailure.class,()->builder.buildAndPush(Fixtures.job("DOCKER","MANUAL"),repo,()->{},()->{}));
  assertThrows(ExecutionFailure.class,()->SafeFiles.inside(repo,"../outside"));
 }
 @Test void missingDockerfileProducesAnActionableProcessEventWithoutStartingBuildkit()throws Exception{
  Path repo=Files.createDirectory(directory.resolve("repo"));var logs=new ArrayList<String>();var calls=new AtomicInteger();
  var builder=new BuildkitImageBuilder(Fixtures.properties(directory),(a,b,c,d,e,f,g)->{calls.incrementAndGet();return "";});
  var failure=assertThrows(ExecutionFailure.class,()->builder.buildAndPush(Fixtures.job("DOCKER","MANUAL"),repo,()->{},()->{},logs::add));
  assertEquals("BUILD_FAILED",failure.code);assertEquals(0,calls.get());
  assertTrue(logs.stream().anyMatch(line->line.contains("Dockerfile was not found in the checked-out commit")));
  assertTrue(logs.stream().noneMatch(line->line.contains(directory.toString())));
 }
 @Test void directoryAtDockerfilePathProducesAnActionableProcessEvent()throws Exception{
  Path repo=Files.createDirectory(directory.resolve("repo"));Files.createDirectory(repo.resolve("Dockerfile"));var logs=new ArrayList<String>();
  var builder=new BuildkitImageBuilder(Fixtures.properties(directory),(a,b,c,d,e,f,g)->{fail("BuildKit must not run with invalid configuration");return "";});
  assertThrows(ExecutionFailure.class,()->builder.buildAndPush(Fixtures.job("DOCKER","MANUAL"),repo,()->{},()->{},logs::add));
  assertTrue(logs.stream().anyMatch(line->line.contains("Dockerfile is not a regular file")));
 }
 @Test void sourceTokenIsNotInGitArgumentsOrRepositoryAndTemporaryAuthIsRemoved()throws Exception{
  var commands=new ArrayList<List<String>>();var seen=new AtomicInteger();
  CommandExecutor runner=(args,cwd,env,timeout,guard,lines,capture)->{
   commands.add(args);assertFalse(args.toString().contains("private-token"));
   try{String contents=Files.readString(Path.of(env.get("GIT_CONFIG_GLOBAL")));assertTrue(contents.contains("Authorization: Basic"));seen.incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}
   return args.contains("rev-parse")?Fixtures.SHA:"";
  };
  Path repo=new GitCheckout(runner).checkout(Fixtures.job("DOCKER","MANUAL"),new SourceContext("https://github.com/example/web.git","private-token",Instant.now().plusSeconds(3600),null),directory,()->{});
  assertEquals(5,seen.get());assertTrue(commands.stream().anyMatch(args->args.contains(Fixtures.SHA)));assertFalse(Files.exists(directory.resolve("source-auth.config")));assertTrue(repo.startsWith(directory));
 }
 @Test void checkoutRejectsUntrustedSourceHostsBeforeStartingProcesses(){
  assertFalse(Fixtures.properties(directory).toString().contains("test-only"));
  var calls=new AtomicInteger();CommandExecutor runner=(a,b,c,d,e,f,g)->{calls.incrementAndGet();return "";};
  assertThrows(ExecutionFailure.class,()->new GitCheckout(runner).checkout(Fixtures.job("DOCKER","MANUAL"),new SourceContext("https://evil.example/web.git","token",Instant.now().plusSeconds(3600),null),directory,()->{}));assertEquals(0,calls.get());
 }
}
