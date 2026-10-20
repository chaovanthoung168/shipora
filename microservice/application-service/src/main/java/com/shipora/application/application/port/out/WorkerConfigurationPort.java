package com.shipora.application.application.port.out;
import java.util.*;
public interface WorkerConfigurationPort {
 Values workerConfiguration(UUID org,UUID app,long expectedVersion);
 record Values(long version,Map<String,String> environment,Map<String,SecretCipher.Encrypted> secrets){
  public Values{environment=Map.copyOf(environment);secrets=Map.copyOf(secrets);}
  @Override public String toString(){return "Values[redacted]";}
 }
}
