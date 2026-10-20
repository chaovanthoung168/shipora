package com.shipora.worker.adapter.out.git;
import com.shipora.pipeline.Protocol.*;
import com.shipora.worker.application.port.*;
import com.shipora.worker.adapter.out.process.SafeFiles;
import com.shipora.worker.domain.ExecutionFailure;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
public final class GitCheckout implements SourceCheckout {
 private final CommandExecutor commands;
 public GitCheckout(CommandExecutor commands){this.commands=commands;}
 @Override public Path checkout(Job job,SourceContext source,Path directory,Guard guard){
  Path auth=directory.resolve("source-auth.config");
  try{
   if(source.token()==null||source.expiresAt()==null||!source.expiresAt().isAfter(Instant.now().plusSeconds(10)))throw new ExecutionFailure("SOURCE_UNAVAILABLE");
   if(!source.cloneUrl().matches("https://github\\.com/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+\\.git"))throw new ExecutionFailure("SOURCE_UNAVAILABLE");
   String sha=job.deployment().commitSha();if(!sha.matches("[a-f0-9]{40}|[a-f0-9]{64}"))throw new ExecutionFailure("SOURCE_UNAVAILABLE");
   Path home=SafeFiles.directory(directory.resolve("git-home")),hooks=SafeFiles.directory(directory.resolve("empty-hooks"));
   String header=Base64.getEncoder().encodeToString(("x-access-token:"+source.token()).getBytes(StandardCharsets.UTF_8));
   Files.writeString(auth,"[http \"https://github.com/\"]\n extraHeader = Authorization: Basic "+header+"\n followRedirects = false\n sslVerify = true\n[core]\n hooksPath = \""+hooks.toString().replace("\\","/").replace("\"","\\\"")+"\"\n");
   SafeFiles.privateMode(auth,false);
   var env=Map.of("HOME",home.toString(),"USERPROFILE",home.toString(),"GIT_CONFIG_GLOBAL",auth.toString(),"GIT_CONFIG_NOSYSTEM","1","GIT_TERMINAL_PROMPT","0","GIT_ASKPASS","");
   Path repo=SafeFiles.directory(directory.resolve("repository"));
   run(List.of("git","init",sha.length()==64?"--object-format=sha256":"--object-format=sha1","."),repo,env,guard,false);
   run(List.of("git","remote","add","origin",source.cloneUrl()),repo,env,guard,false);
   run(List.of("git","-c","protocol.file.allow=never","fetch","--no-tags","--depth=1","origin",sha),repo,env,guard,false);
   run(List.of("git","checkout","--force","--detach","FETCH_HEAD"),repo,env,guard,false);
   if(!sha.equals(run(List.of("git","rev-parse","HEAD"),repo,env,guard,true).strip()))throw new ExecutionFailure("SOURCE_UNAVAILABLE");
   SafeFiles.rejectEscapingLinks(repo);return repo;
  }catch(ExecutionFailure.LeaseLost e){throw e;}
  catch(Exception e){throw new ExecutionFailure("SOURCE_UNAVAILABLE");}
  finally{try{Files.deleteIfExists(auth);}catch(Exception ignored){}}
 }
 private String run(List<String> args,Path directory,Map<String,String> env,Guard guard,boolean capture){return commands.run(args,directory,env,Duration.ofMinutes(5),guard,ignored->{},capture);}
}
