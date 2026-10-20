package com.shipora.deployment.adapter.out.http;
import com.shipora.deployment.application.port.out.AutomaticCatalog;
import com.shipora.deployment.configuration.CatalogProperties;
import com.shipora.deployment.domain.ApplicationSnapshot;
import com.shipora.pipeline.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class HttpAutomaticCatalog implements AutomaticCatalog,AutoCloseable {
 private final InternalHttp http;private final CatalogProperties properties;private final String authorization;private final UUID organization;private final HttpDeploymentCatalog catalog;
 public HttpAutomaticCatalog(InternalHttp http,CatalogProperties properties,String authorization,UUID org){this.http=http;this.properties=properties;this.authorization=authorization;organization=org;catalog=new HttpDeploymentCatalog(properties,ignored->authorization);}
 public List<UUID> matching(UUID org,UUID repo,String branch){
  check(org);var result=new ArrayList<UUID>();
  for(int page=0;page<=10;page++){
   var rows=http.send(properties.applicationUrl(),"/internal/automation/applications?repositoryId="+repo+"&branch="+URLEncoder.encode(branch,StandardCharsets.UTF_8)+"&page="+page+"&size=100","GET",authorization,null);
   if(!rows.isArray())throw new InternalHttp.RemoteFailure(503);
   for(var row:rows){if(!org.toString().equals(row.path("organizationId").asText()))throw new InternalHttp.RemoteFailure(403);result.add(UUID.fromString(row.path("id").asText()));}
   if(result.size()>1000)throw new InternalHttp.RemoteFailure(503);
   if(rows.size()<100)return List.copyOf(result);
  }
  throw new InternalHttp.RemoteFailure(503);
 }
 public ApplicationSnapshot configuration(UUID org,UUID app){check(org);return catalog.configuration(org,app,authorization);}
 private void check(UUID org){if(!organization.equals(org))throw new InternalHttp.RemoteFailure(403);}
 public void close(){catalog.close();}
}
