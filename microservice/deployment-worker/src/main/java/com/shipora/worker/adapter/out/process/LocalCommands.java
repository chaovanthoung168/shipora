package com.shipora.worker.adapter.out.process;
import com.shipora.worker.application.port.*;
import com.shipora.worker.domain.ExecutionFailure;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
public final class LocalCommands implements CommandExecutor {
 @Override public String run(List<String> command,Path directory,Map<String,String> environment,Duration timeout,Guard guard,Consumer<String> lines,boolean capture){
  Process process=null;var readers=Executors.newVirtualThreadPerTaskExecutor();
  try{
   guard.check();var builder=new ProcessBuilder(command).directory(directory.toFile());builder.environment().clear();
   for(String key:List.of("PATH","Path","SystemRoot","WINDIR","PATHEXT"))if(System.getenv(key)!=null)builder.environment().put(key,System.getenv(key));
   builder.environment().putAll(environment);process=builder.start();Process current=process;
   var stdout=readers.submit(()->drain(current.getInputStream(),lines,capture));var stderr=readers.submit(()->drain(current.getErrorStream(),lines,false));
   long deadline=System.nanoTime()+timeout.toNanos();
   while(!process.waitFor(200,TimeUnit.MILLISECONDS)){
    guard.check();if(System.nanoTime()>deadline)throw new ExecutionFailure("WORKER_FAILED");
    if(stdout.isDone())stdout.get();if(stderr.isDone())stderr.get();
   }
   String output=stdout.get(5,TimeUnit.SECONDS);stderr.get(5,TimeUnit.SECONDS);guard.check();
   if(process.exitValue()!=0)throw new ExecutionFailure("WORKER_FAILED");return output;
  }catch(ExecutionFailure e){throw e;}
  catch(InterruptedException e){Thread.currentThread().interrupt();throw new ExecutionFailure.LeaseLost();}
  catch(Exception e){throw new ExecutionFailure("WORKER_FAILED");}
  finally{
   if(process!=null){if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
    try{process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close();}catch(IOException ignored){} }
   readers.shutdownNow();
  }
 }
 private String drain(InputStream input,Consumer<String> lines,boolean capture)throws IOException{
  var output=new ByteArrayOutputStream();var line=new ByteArrayOutputStream();boolean oversized=false;byte[] buffer=new byte[4096];int count;
  while((count=input.read(buffer))!=-1){
   if(capture){if(output.size()+count>65536)throw new IOException("Output bound");output.write(buffer,0,count);}
   for(int i=0;i<count;i++){int b=buffer[i]&255;if(b==10){if(!oversized)lines.accept(line.toString(StandardCharsets.UTF_8));line.reset();oversized=false;}
    else if(line.size()<8192&&!oversized)line.write(b);else{line.reset();oversized=true;}}
  }
  if(line.size()>0&&!oversized)lines.accept(line.toString(StandardCharsets.UTF_8));return output.toString(StandardCharsets.UTF_8);
 }
}
