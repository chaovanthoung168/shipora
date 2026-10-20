package com.shipora.worker;
import com.shipora.worker.adapter.out.docker.*;
import com.shipora.worker.application.port.*;
import com.shipora.worker.domain.ExecutionFailure;
import com.shipora.pipeline.Protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DockerRuntimeTest {
 @TempDir Path directory;
 @Test void sendsEnvironmentOnlyOverTransportStdinAndPreservesDeploymentFence(){
  List<Map<String,Object>> calls=new ArrayList<>();RemoteDocker remote=(server,body,guard)->{guard.check();calls.add(body);return Map.of("port",34001);};
  var runtime=new DockerRuntime(remote,Fixtures.properties(directory));var job=Fixtures.job("DOCKER","MANUAL");
  var server=new ServerContext(Fixtures.ORG,Fixtures.SERVER,"127.0.0.1",22,"root","ref","SHA256:dummy");
  var handle=runtime.deploy(job,new ApplicationContext(3,Map.of("PRIVATE","line1\nline2"),null),server,Fixtures.IMAGE,()->{});
  assertEquals("http://127.0.0.1:34001",runtime.verify(handle,()->{}));runtime.rollback(handle,()->{});
  assertEquals(List.of("deploy","verify","rollback"),calls.stream().map(c->c.get("action")).toList());assertEquals(job.fence(),calls.getFirst().get("fence"));
  assertFalse(handle.toString().contains("line1"));assertEquals(Map.of("PRIVATE","line1\nline2"),calls.getFirst().get("environment"));
 }
 @Test void unauthorizedSshTargetsFailBeforeAnyConnectionOrKeyUse(){
  var transport=new SshDockerTransport(Fixtures.properties(directory));
  assertThrows(ExecutionFailure.class,()->transport.execute(new ServerContext(Fixtures.ORG,Fixtures.SERVER,"127.0.0.1",22,"root","unknown","SHA256:dummy"),Map.of(),()->{}));
 }
 @Test void staticRemoteProgramIsQuotedAsASingleShellArgument(){
  assertEquals("'hello'\"'\"'$(unsafe)'",SshDockerTransport.quote("hello'$(unsafe)"));
 }
}
