package com.shipora.worker.adapter.out.buildkit;
import com.shipora.pipeline.Protocol.Job;
import com.shipora.worker.application.port.*;
import com.shipora.worker.adapter.out.process.SafeFiles;
import com.shipora.worker.configuration.WorkerProperties;
import com.shipora.worker.domain.ExecutionFailure;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
public final class BuildkitImageBuilder implements ImageBuilder {
 private final WorkerProperties properties;private final CommandExecutor commands;private final JsonMapper json=JsonMapper.builder().build();
 public BuildkitImageBuilder(WorkerProperties properties,CommandExecutor commands){this.properties=properties;this.commands=commands;}
 @Override public String buildAndPush(Job job,Path repository,Guard guard,Runnable pushing){
  return buildAndPush(job,repository,guard,pushing,ignored->{});
 }
 @Override public String buildAndPush(Job job,Path repository,Guard guard,Runnable pushing,Consumer<String> logs){
  var output=new BuildOutput(logs);
  try{
   var config=job.deployment().configuration();Path context=buildPath(repository,config.buildContext(),"build context",logs),dockerfile=buildPath(repository,config.dockerfilePath(),"Dockerfile",logs);
   if(!Files.isDirectory(context)){logs.accept("Build configuration error: the configured build context is not a directory.");throw new ExecutionFailure("BUILD_FAILED");}
   if(!Files.isRegularFile(dockerfile)){logs.accept("Build configuration error: the configured Dockerfile is not a regular file.");throw new ExecutionFailure("BUILD_FAILED");}
   String repositoryName=properties.imageRepository()+"/"+job.deployment().organizationId()+"/"+job.deployment().applicationId();
   String tag=repositoryName+":"+job.deployment().id();Path metadata=repository.getParent().resolve("build-metadata.json");Files.deleteIfExists(metadata);
   var command=new ArrayList<>(properties.buildctlCommand());command.addAll(List.of("--addr",properties.buildkitAddress()));
   if(properties.buildkitCa()!=null)command.addAll(List.of("--tlscacert",properties.buildkitCa().toString()));
   if(properties.buildkitCert()!=null)command.addAll(List.of("--tlscert",properties.buildkitCert().toString()));
   if(properties.buildkitKey()!=null)command.addAll(List.of("--tlskey",properties.buildkitKey().toString()));
   command.addAll(List.of("build","--frontend","dockerfile.v0","--local","context="+context,"--local","dockerfile="+dockerfile.getParent(),
     "--opt","filename="+dockerfile.getFileName(),"--output","type=image,name="+tag+",push=true","--metadata-file",metadata.toString(),"--progress","rawjson"));
   var env=new HashMap<String,String>();env.put("HOME",SafeFiles.directory(repository.getParent().resolve("build-home")).toString());
   if(properties.dockerConfig()!=null)env.put("DOCKER_CONFIG",properties.dockerConfig().toString());
   commands.run(command,repository,env,properties.buildTimeout(),guard,line->{
    try{var event=json.readTree(line);output.accept(event);var vertices=event.path("vertexes");if(vertices.isArray())for(var vertex:vertices)if("exporting to image".equals(vertex.path("name").asText()))pushing.run();}
    catch(tools.jackson.core.JacksonException ignored){logs.accept(line);/* Plain buildctl errors pass through the same job-scoped redactor. */}
   },false);
   pushing.run();guard.check();
   if(!Files.isRegularFile(metadata)||Files.size(metadata)>1048576)throw new ExecutionFailure("PUSH_FAILED");
   String digest=json.readTree(Files.readAllBytes(metadata)).path("containerimage.digest").asText("");
   if(!digest.matches("sha256:[a-f0-9]{64}"))throw new ExecutionFailure("PUSH_FAILED");return repositoryName+"@"+digest;
  }catch(ExecutionFailure.LeaseLost e){throw e;}catch(ExecutionFailure e){throw e;}catch(Exception e){throw new ExecutionFailure("BUILD_FAILED");}
  finally{output.finish();}
 }
 private Path buildPath(Path repository,String relative,String kind,Consumer<String> logs)throws IOException{
  try{return SafeFiles.inside(repository,relative);}
  catch(NoSuchFileException missing){
   logs.accept("Build configuration error: the configured "+kind+" was not found in the checked-out commit. Check the application's build settings and commit the required files before retrying.");
   throw new ExecutionFailure("BUILD_FAILED");
  }
 }
}
