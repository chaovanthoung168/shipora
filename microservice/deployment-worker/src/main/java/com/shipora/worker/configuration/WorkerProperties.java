package com.shipora.worker.configuration;
import com.shipora.pipeline.InternalHttp;
import org.springframework.boot.context.properties.*;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
@ConfigurationProperties("shipora.worker")
public record WorkerProperties(@DefaultValue("false") boolean enabled,@DefaultValue("http://localhost:8083") String deploymentUrl,
 @DefaultValue("http://localhost:8082") String applicationUrl,@DefaultValue("http://localhost:8084") String gitUrl,@DefaultValue("http://localhost:8081") String projectUrl,
 @DefaultValue("shipora-worker") String username,@DefaultValue("") String password,@DefaultValue("./.shipora/worker") Path workspace,
 @DefaultValue("unix:///run/buildkit/buildkitd.sock") String buildkitAddress,List<String> buildctlCommand,Path dockerConfig,
 @DefaultValue("") String imageRepository,@DefaultValue("30m") Duration buildTimeout,@DefaultValue("5m") Duration rolloutTimeout,
 @DefaultValue("1") int concurrency,List<SshTarget> sshTargets,@DefaultValue("") String baseDomain,@DefaultValue("") String ingressClass,
 Path buildkitCa,Path buildkitCert,Path buildkitKey,@DefaultValue("false") boolean allowInsecureBuildkit) {
 public WorkerProperties {
  InternalHttp.origin(deploymentUrl);InternalHttp.origin(applicationUrl);InternalHttp.origin(gitUrl);InternalHttp.origin(projectUrl);
  buildctlCommand=buildctlCommand==null||buildctlCommand.isEmpty()?List.of("buildctl"):List.copyOf(buildctlCommand);
  sshTargets=sshTargets==null?List.of():List.copyOf(sshTargets);workspace=workspace.toAbsolutePath().normalize();
  if(concurrency<1||concurrency>4||buildTimeout.toSeconds()<1||buildTimeout.toHours()>2||rolloutTimeout.toSeconds()<1||rolloutTimeout.toMinutes()>30)throw new IllegalArgumentException("Invalid worker bounds.");
  if(!baseDomain.isEmpty()&&(baseDomain.length()>200||!baseDomain.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")))throw new IllegalArgumentException("Invalid base domain.");
  if(enabled){
   if(password.isBlank() || !imageRepository.matches("[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]{1,5})?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*"))throw new IllegalArgumentException("Worker credentials and a registry repository are required.");
   String registry=imageRepository.split("/",2)[0];
   if(!imageRepository.contains("/")||!(registry.contains(".")||registry.contains(":")||registry.equals("localhost")))throw new IllegalArgumentException("Image repository must include an explicit registry and namespace.");
   if(!buildkitAddress.startsWith("unix://")&&!buildkitAddress.startsWith("tcp://"))throw new IllegalArgumentException("BuildKit must use a Unix or TCP address.");
   if(buildkitAddress.startsWith("tcp://")&&!allowInsecureBuildkit&&(buildkitCa==null||buildkitCert==null||buildkitKey==null))throw new IllegalArgumentException("TCP BuildKit requires TLS or explicit trusted-network opt-in.");
  }
 }
 @Override public String toString(){return "WorkerProperties[redacted]";}
 public record SshTarget(UUID organizationId,String credentialRef,String host,@DefaultValue("22") int port,String username,String fingerprint,Path privateKey,
   @DefaultValue("ssh-ed25519") String hostKeyAlgorithm,@DefaultValue("") String passphraseEnvironmentVariable){}
}
