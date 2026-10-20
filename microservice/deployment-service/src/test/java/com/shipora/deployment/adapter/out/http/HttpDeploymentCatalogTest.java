package com.shipora.deployment.adapter.out.http;

import com.shipora.deployment.configuration.CatalogProperties;
import com.shipora.deployment.domain.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpDeploymentCatalogTest {
    final UUID org=UUID.randomUUID(),app=UUID.randomUUID(),project=UUID.randomUUID(),repo=UUID.randomUUID(),serverId=UUID.randomUUID(),target=UUID.randomUUID();
    final JsonMapper json=JsonMapper.builder().build();final List<String> authorizations=new CopyOnWriteArrayList<>();final List<String> paths=new CopyOnWriteArrayList<>();
    HttpServer server;ExecutorService executor;HttpDeploymentCatalog catalog;
    String runtime="DOCKER",targetState="ACTIVE",mode="ok";boolean available=true;
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/",this::respond);server.start();URI origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        catalog=new HttpDeploymentCatalog(new CatalogProperties(origin,origin,origin),ignored->"Basic caller-credentials");
    }
    @AfterEach void stop(){catalog.close();server.stop(0);executor.shutdownNow();}
    void respond(HttpExchange exchange)throws java.io.IOException {
        try(exchange){
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));String path=exchange.getRequestURI().getPath();paths.add(path);int status=200;Object body;
            if(mode.matches("401|403|404|500")){status=Integer.parseInt(mode);body=Map.of("message","private-provider-detail");}
            else if(mode.equals("redirect")){status=302;exchange.getResponseHeaders().set("Location","/redirected");body=Map.of();}
            else if(mode.equals("large")){body="x".repeat(300000);}
            else if(path.equals("/api/applications/"+app)){
                var data=new LinkedHashMap<String,Object>();data.put("id",mode.equals("wrongId")?UUID.randomUUID():app);data.put("organizationId",mode.equals("wrongOrg")?UUID.randomUUID():org);
                data.put("projectId",project);data.put("repositoryId",repo);data.put("runtimeType",runtime);data.put("serverId",runtime.equals("DOCKER")?serverId:null);data.put("kubernetesTargetId",runtime.equals("KUBERNETES")?target:null);
                data.put("name","Web");data.put("slug","web");data.put("branch","main");data.put("dockerfilePath","Dockerfile");data.put("buildContext",".");data.put("healthPath","/");data.put("containerPort",mode.equals("badPort")?0:3000);data.put("version",7);
                data.put("extraField","ignored");body=data;
            }else if(path.equals("/api/projects/"+project))body=Map.of("id",project,"organizationId",org);
            else if(path.equals("/api/git/repositories/"+repo))body=Map.of("id",repo,"available",available);
            else if(path.equals("/api/servers/"+serverId))body=Map.of("id",serverId,"organizationId",org,"status",targetState);
            else if(path.equals("/api/kubernetes-targets/"+target))body=Map.of("id",target,"organizationId",org,"status",targetState);
            else{status=404;body=Map.of();}
            String value=mode.equals("malformed")?"not-json":json.writeValueAsString(body);byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }
    }
    @Test void dockerCatalogForwardsOnlyTheAuthenticatedCallerAndSnapshotsMetadata(){
        var result=catalog.requireReadyApplication(org,app);assertEquals("DOCKER",result.runtimeType());assertEquals(serverId,result.serverId());assertEquals(7,result.version());
        assertEquals(4,paths.size());assertTrue(authorizations.stream().allMatch("Basic caller-credentials"::equals));assertFalse(result.toString().contains("extraField"));
    }
    @Test void kubernetesChecksNamespaceTargetInsteadOfDockerServer(){
        runtime="KUBERNETES";var result=catalog.requireReadyApplication(org,app);assertEquals(target,result.kubernetesTargetId());assertNull(result.serverId());
        assertTrue(paths.contains("/api/kubernetes-targets/"+target));assertFalse(paths.stream().anyMatch(path->path.startsWith("/api/servers")));
    }
    @Test void unavailableRepositoryOrInactiveTargetPreventsQueueing(){
        available=false;assertEquals(DeploymentException.Kind.CONFLICT,assertThrows(DeploymentException.class,()->catalog.requireReadyApplication(org,app)).getKind());
        available=true;targetState="UNVERIFIED";assertEquals(DeploymentException.Kind.CONFLICT,assertThrows(DeploymentException.class,()->catalog.requireReadyApplication(org,app)).getKind());
    }
    @Test void crossTenantAndWrongIdentityRemainNotFound(){
        for(String behavior:List.of("wrongId","wrongOrg")){mode=behavior;assertEquals(DeploymentException.Kind.NOT_FOUND,assertThrows(DeploymentException.class,()->catalog.requireReadyApplication(org,app)).getKind());}
    }
    @Test void providerErrorsAreTranslatedWithoutLeakingBodies(){
        for(var entry:Map.of("401",DeploymentException.Kind.FORBIDDEN,"403",DeploymentException.Kind.FORBIDDEN,"404",DeploymentException.Kind.NOT_FOUND,"500",DeploymentException.Kind.UNAVAILABLE).entrySet()){
            mode=entry.getKey();var failure=assertThrows(DeploymentException.class,()->catalog.requireReadyApplication(org,app));assertEquals(entry.getValue(),failure.getKind());assertFalse(failure.getMessage().contains("private-provider-detail"));
        }
    }
    @Test void redirectsMalformedOversizedResponsesAndInvalidMetadataFailClosed(){
        for(String behavior:List.of("redirect","malformed","large","badPort")){mode=behavior;assertEquals(DeploymentException.Kind.UNAVAILABLE,assertThrows(DeploymentException.class,()->catalog.requireReadyApplication(org,app)).getKind());}
        assertFalse(paths.contains("/redirected"));
    }
    @Test void rejectsUntrustedOriginsWithEmbeddedCredentialsPathsAndQueries(){
        for(String value:List.of("ftp://cluster","https://user:pass@cluster","https://cluster/path","https://cluster?secret=value")){
            var uri=URI.create(value);assertThrows(IllegalArgumentException.class,()->new HttpDeploymentCatalog(new CatalogProperties(uri,uri,uri),ignored->"Basic ignored"));
        }
    }
}
