package com.shipora.pipeline;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class PipelineAccounts {
 private PipelineAccounts(){}
 public static InMemoryUserDetailsManager users(List<UserDetails> primary,Environment env,PasswordEncoder encoder,UUID org,String... automationRoles){
  var users=new ArrayList<>(primary);
  String worker=env.getProperty("shipora.local.worker-username",env.getProperty("LOCAL_WORKER_USERNAME","shipora-worker"));
  String password=env.getProperty("shipora.local.worker-password",env.getProperty("LOCAL_WORKER_PASSWORD",""));
  if(!password.isBlank() && users.stream().anyMatch(u->u.getUsername().equals(worker) && u.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_PIPELINE_WORKER"))))
   throw new IllegalArgumentException("Worker and API usernames must be different.");
  if(!password.isBlank() && users.stream().noneMatch(u->u.getUsername().equals(worker)))
   users.add(User.withUsername(worker).password(encoder.encode(password)).authorities("ROLE_PIPELINE_WORKER","ROLE_DEPLOYMENT_WORKER","ORG_"+org).build());
  String automation=env.getProperty("SHIPORA_AUTOMATION_USERNAME","shipora-automation"),secret=env.getProperty("SHIPORA_AUTOMATION_PASSWORD","");
  if(!secret.isBlank()){
   if(users.stream().anyMatch(u->u.getUsername().equals(automation)) || automation.equals(worker))throw new IllegalArgumentException("Pipeline identities must be separate.");
   var authorities=new ArrayList<String>(List.of("ROLE_PIPELINE_AUTOMATION","ORG_"+org));authorities.addAll(List.of(automationRoles));
   users.add(User.withUsername(automation).password(encoder.encode(secret)).authorities(authorities.toArray(String[]::new)).build());
  }
  return new InMemoryUserDetailsManager(users);
 }
 public static String automationAuth(Environment env){
  String secret=env.getProperty("SHIPORA_AUTOMATION_PASSWORD","");
  if(secret.isBlank())throw new IllegalStateException("SHIPORA_AUTOMATION_PASSWORD is required for automatic push delivery.");
  return basic(env.getProperty("SHIPORA_AUTOMATION_USERNAME","shipora-automation"),secret);
 }
 public static String basic(String username,String password){return "Basic "+Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));}
}
