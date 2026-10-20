package com.shipora.worker.application;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public final class ProcessLogSanitizer {
 private static final Pattern ANSI=Pattern.compile("\u001b\\[[0-?]*[ -/]*[@-~]");
 private static final Pattern ASSIGNMENT=Pattern.compile("(?i)((?:[a-z0-9_]*(?:token|password|passwd|secret|api_key|private_key|credential|auth)[a-z0-9_]*|authorization)\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|(?:Basic|Bearer)\\s+\\S+|\\S+)");
 private final Set<String> secrets=new TreeSet<>(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
 private boolean privateKey;
 public synchronized void hide(Collection<String> values){
  for(var value:values)if(value!=null&&!value.isBlank()){
   secrets.add(value);secrets.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
  }
 }
 public synchronized String clean(String value){
  if(value==null)return "";
  String line=ANSI.matcher(value).replaceAll("").replaceAll("[\\p{Cntrl}&&[^\\t]]"," ");
  if(line.contains("-----BEGIN ")&&line.contains("PRIVATE KEY-----"))privateKey=true;
  if(privateKey){if(line.contains("-----END ")&&line.contains("PRIVATE KEY-----"))privateKey=false;return "[private key output redacted]";}
  for(String secret:secrets)line=line.replace(secret,"[redacted]");
  line=ASSIGNMENT.matcher(line).replaceAll("$1[redacted]")
   .replaceAll("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@","$1[redacted]@")
   .replaceAll("(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+)","[redacted]");
  return line.length()>2048?line.substring(0,2020)+" [line truncated]":line;
 }
}
