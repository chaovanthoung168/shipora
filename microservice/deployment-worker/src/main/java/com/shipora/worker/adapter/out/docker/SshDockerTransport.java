package com.shipora.worker.adapter.out.docker;
import com.shipora.pipeline.Protocol.ServerContext;
import com.shipora.worker.application.port.*;
import com.shipora.worker.configuration.WorkerProperties;
import com.shipora.worker.domain.ExecutionFailure;
import net.schmizz.sshj.*;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetAddress;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
public final class SshDockerTransport implements RemoteDocker {
 private final WorkerProperties properties;private final JsonMapper json=JsonMapper.builder().build();private final String script;
 public SshDockerTransport(WorkerProperties properties){
  this.properties=properties;
  try(var stream=getClass().getResourceAsStream("/runtime/docker_runtime.py")){script=new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);}
  catch(Exception e){throw new IllegalStateException("Docker runtime script is unavailable.");}
 }
 public Map<String,Object> execute(ServerContext server,Map<String,Object> request,Guard guard){
  var target=properties.sshTargets().stream().filter(t->matches(t,server)).findFirst().orElseThrow(()->new ExecutionFailure("CONFIGURATION_UNAVAILABLE"));
  char[] passphrase=target.passphraseEnvironmentVariable().isBlank()?new char[0]:System.getenv().getOrDefault(target.passphraseEnvironmentVariable(),"").toCharArray();
  var config=new DefaultConfig();config.setKeyAlgorithms(config.getKeyAlgorithms().stream().filter(a->a.getName().equals(target.hostKeyAlgorithm())).toList());
  var readers=Executors.newVirtualThreadPerTaskExecutor();SSHClient client=new SSHClient(config);
  try(client){
   guard.check();client.setConnectTimeout(5000);client.setTimeout(60000);client.getConnection().getKeepAlive().setKeepAliveInterval(10);
   client.addHostKeyVerifier(FingerprintVerifier.getInstance(target.fingerprint()));
   client.connect(InetAddress.ofLiteral(target.host()),target.port());
   guard.check();client.authPublickey(target.username(),client.loadKeys(target.privateKey().toString(),passphrase));
   try(var session=client.startSession()){
    var command=session.exec("python3 -c "+quote(script));
    byte[] input=json.writeValueAsBytes(request);if(input.length>4*1024*1024)throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");
    command.getOutputStream().write(input);command.getOutputStream().close();
    var output=readers.submit(()->{
     var bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
     while((count=command.getInputStream().read(buffer))!=-1){if(bytes.size()+count>65536)throw new java.io.IOException("Remote output bound");bytes.write(buffer,0,count);}return bytes.toByteArray();
    });
    readers.submit(()->{try{command.getErrorStream().transferTo(java.io.OutputStream.nullOutputStream());}catch(Exception ignored){}});
    awaitExit(command,guard,properties.rolloutTimeout().plusMinutes(2));
    guard.check();var result=json.readTree(output.get(5,TimeUnit.SECONDS));
    if(command.getExitStatus()!=0||!result.path("ok").asBoolean(false)){
     if("STALE_FENCE".equals(result.path("code").asText()))throw new ExecutionFailure.LeaseLost();throw new ExecutionFailure.RuntimeRejected(result.path("code").asText(""));
    }
    var values=new HashMap<String,Object>();values.put("port",result.path("port").asInt());return values;
   }
  }catch(ExecutionFailure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ExecutionFailure.LeaseLost();}
  catch(Exception e){throw new ExecutionFailure("DEPLOY_FAILED");}
  finally{Arrays.fill(passphrase,'\0');try{client.close();}catch(Exception ignored){}readers.shutdownNow();}
 }
 static void awaitExit(net.schmizz.sshj.connection.channel.direct.Session.Command command,Guard guard,Duration timeout)throws InterruptedException{
  long deadline=System.nanoTime()+timeout.toNanos();
  while(command.getExitStatus()==null){
   guard.check();if(!command.isOpen()||System.nanoTime()>deadline)throw new ExecutionFailure("DEPLOY_FAILED");
   // SSHJ join(timeout) throws on a live command; its timeout is not a polling result.
   Thread.sleep(200);
  }
 }
 private boolean matches(WorkerProperties.SshTarget t,ServerContext s){
  try{return t.organizationId().equals(s.organizationId())&&t.credentialRef().equals(s.credentialRef())&&t.port()==s.port()&&t.username().equals(s.username())
    &&t.fingerprint().equals(s.fingerprint())&&InetAddress.ofLiteral(t.host()).equals(InetAddress.ofLiteral(s.host()))&&Files.isRegularFile(t.privateKey());}
  catch(RuntimeException e){return false;}
 }
 public static String quote(String value){return "'"+value.replace("'","'\"'\"'")+"'";}
}
