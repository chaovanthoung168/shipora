package com.shipora.worker.adapter.out.process;
import com.shipora.worker.domain.ExecutionFailure;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
public final class SafeFiles {
 private SafeFiles(){}
 public static Path directory(Path path)throws IOException{Files.createDirectories(path);privateMode(path,true);return path;}
 public static void privateMode(Path path,boolean directory)throws IOException{
  if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
 }
 public static Path inside(Path root,String relative)throws IOException{
  Path realRoot=root.toRealPath();Path value=realRoot.resolve(relative).normalize();
  if(!value.startsWith(realRoot)||!value.toRealPath().startsWith(realRoot))throw new ExecutionFailure("CONFIGURATION_UNAVAILABLE");
  return value.toRealPath();
 }
 public static void rejectEscapingLinks(Path root)throws IOException{
  Path real=root.toRealPath();
  try(var entries=Files.walk(real)){for(Path path:entries.toList())if(Files.isSymbolicLink(path)){
   Path destination=path.getParent().resolve(Files.readSymbolicLink(path)).normalize();
   if(!destination.startsWith(real)||!path.toRealPath().startsWith(real))throw new ExecutionFailure("SOURCE_UNAVAILABLE");
  }}
 }
 public static void deleteWorkspace(Path root,Path directory)throws IOException{
  Path boundary=root.toRealPath(),target=directory.toAbsolutePath().normalize();
  if(target.equals(boundary)||!target.startsWith(boundary)||!target.getParent().toRealPath().equals(boundary)||Files.isSymbolicLink(target))throw new IOException("Workspace cleanup boundary");
  if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS))return;
  Files.walkFileTree(target,new SimpleFileVisitor<>(){
   @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
   @Override public FileVisitResult postVisitDirectory(Path dir,IOException failure)throws IOException{if(failure!=null)throw failure;Files.delete(dir);return FileVisitResult.CONTINUE;}
  });
 }
}
