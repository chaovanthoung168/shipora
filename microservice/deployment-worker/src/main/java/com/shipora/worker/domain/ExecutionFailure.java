package com.shipora.worker.domain;
public class ExecutionFailure extends RuntimeException {
 public final String code;
 public ExecutionFailure(String code){super(code);this.code=code;}
 public static final class RuntimeRejected extends ExecutionFailure {
  public final String detail;
  public RuntimeRejected(String remoteCode){super("DEPLOY_FAILED");detail=switch(remoteCode){
   case "REGISTRY_ACCESS_DENIED"->"Docker could not authenticate to the configured image registry.";
   case "REGISTRY_CONNECTION_FAILED"->"Docker could not reach the image registry.";
   case "REGISTRY_TLS_FAILED"->"Docker rejected the image registry's TLS certificate.";
   case "IMAGE_PLATFORM_UNSUPPORTED"->"The published image does not support the target server's platform.";
   case "IMAGE_EXTRACTION_FAILED"->"Docker could not extract the published image layers on the target server.";
   case "RUNTIME_STORAGE_FULL"->"The target server has insufficient storage for the image.";
   case "PULL_FAILED"->"Docker could not pull the published image on the target server.";
   case "DOCKER_API_FAILED"->"The target Docker API rejected a runtime operation.";
   case "FOREIGN_CONTAINER"->"Runtime ownership validation rejected an existing container.";
   case "HEALTH_CHECK_FAILED"->"The application did not pass the target's HTTP health check.";
   case "CONTAINER_STOPPED"->"The application container stopped before passing its health check.";
   default->"The remote Docker runtime could not complete the operation.";
  };}
 }
 public static final class LeaseLost extends ExecutionFailure {public LeaseLost(){super("LEASE_EXPIRED");}}
}
