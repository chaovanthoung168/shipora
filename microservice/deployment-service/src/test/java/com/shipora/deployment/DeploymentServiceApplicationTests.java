package com.shipora.deployment;

import com.shipora.deployment.domain.ApplicationSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "shipora.local.username=deployment-test","shipora.local.password=test-only",
    "shipora.local.worker-username=worker-test","shipora.local.worker-password=worker-test-only",
    "shipora.local.organization-id=11111111-1111-1111-1111-111111111111"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="DEPLOYMENT_BOOT_TEST_JDBC_URL",matches=".+")
class DeploymentServiceApplicationTests {
    static final UUID ORG=UUID.fromString("11111111-1111-1111-1111-111111111111"),APP=UUID.randomUUID(),PROJECT=UUID.randomUUID(),REPO=UUID.randomUUID(),SERVER=UUID.randomUUID();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final List<String> forwarded=new CopyOnWriteArrayList<>();
    static HttpServer fixture;static ExecutorService executor;
    @Autowired org.springframework.core.env.Environment environment;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties)throws Exception {
        String url=System.getenv("DEPLOYMENT_BOOT_TEST_JDBC_URL");
        if(!url.matches("jdbc:postgresql://[^/]+/shipora_deployment_test_[a-z0-9_]+"))throw new IllegalArgumentException("Use a separate disposable boot database.");
        properties.add("spring.datasource.url",()->url+"?currentSchema=deployment");
        properties.add("spring.datasource.username",()->System.getenv().getOrDefault("DEPLOYMENT_TEST_DB_USER","postgres"));
        properties.add("spring.datasource.password",()->System.getenv().getOrDefault("DEPLOYMENT_TEST_DB_PASSWORD",""));
        fixture=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newVirtualThreadPerTaskExecutor();fixture.setExecutor(executor);
        fixture.createContext("/",exchange->{try(exchange){
            forwarded.add(exchange.getRequestHeaders().getFirst("Authorization"));String path=exchange.getRequestURI().getPath();Object body;int status=200;
            if(path.equals("/api/applications/"+APP))body=new ApplicationSnapshot(APP,ORG,PROJECT,REPO,"DOCKER",SERVER,null,"Web","web","main","Dockerfile",".",3000,"/",2);
            else if(path.equals("/api/projects/"+PROJECT))body=Map.of("id",PROJECT,"organizationId",ORG);
            else if(path.equals("/api/git/repositories/"+REPO))body=Map.of("id",REPO,"available",true);
            else if(path.equals("/api/servers/"+SERVER))body=Map.of("id",SERVER,"organizationId",ORG,"status","ACTIVE");
            else{status=404;body=Map.of();}
            byte[] bytes=JSON.writeValueAsBytes(body);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }});fixture.start();String origin="http://127.0.0.1:"+fixture.getAddress().getPort();
        properties.add("shipora.catalog.application-url",()->origin);properties.add("shipora.catalog.project-url",()->origin);properties.add("shipora.catalog.git-url",()->origin);
    }
    @AfterAll static void close(){if(fixture!=null)fixture.stop(0);if(executor!=null)executor.shutdownNow();}
    @Test void fullHttpWorkflowUsesRealCatalogSecurityFlywayAndPostgres()throws Exception {
        jdbc.execute("TRUNCATE TABLE deployment.deployments,deployment.application_slots,deployment.worker_presence CASCADE");
        try(var http=HttpClient.newHttpClient()){
            var body=Map.of("applicationId",APP,"commitSha","a".repeat(40));
            send(http,"GET","/api/deployments",null,null,null,401);
            send(http,"POST","/internal/deployment-jobs/claims",null,"api",null,403);
            send(http,"POST","/api/deployments",body,"worker","not-allowed",403);
            var created=send(http,"POST","/api/deployments",body,"api","request-1",202);String id=created.path("id").asText();
            assertEquals("QUEUED",created.path("status").asText());assertEquals(2,created.path("configuration").path("version").asLong());assertFalse(created.has("leaseToken"));
            assertEquals(id,send(http,"POST","/api/deployments",body,"api","request-1",202).path("id").asText());
            assertEquals(4,forwarded.size());assertTrue(forwarded.stream().allMatch(auth("api")::equals));
            send(http,"POST","/api/deployments",Map.of("applicationId",APP,"commitSha","b".repeat(40)),"api","request-1",409);
            assertEquals("NO_WORKER",send(http,"GET","/api/deployments/"+id+"/queue-status",null,"api",null,200).path("reason").asText());
            var claim=send(http,"POST","/internal/deployment-jobs/claims",null,"worker",null,200);
            String jobPath="/internal/deployment-jobs/"+claim.path("jobId").asText(),token=claim.path("leaseToken").asText();
            send(http,"POST",jobPath+"/heartbeats",Map.of("leaseToken",token),"worker",null,200);
            var processOutput=Map.of("leaseToken",token,"events",List.of(Map.of("level","INFO","message","npm run build")));
            send(http,"POST",jobPath+"/events",processOutput,"worker",null,204);
            send(http,"PUT",jobPath+"/status",Map.of("leaseToken",token,"expectedStatus","PREPARING","status","SUCCEEDED"),"worker",null,409);
            String[] stages={"PREPARING","BUILDING","PUSHING","DEPLOYING","VERIFYING","SUCCEEDED"};
            for(int i=0;i<stages.length-1;i++){
                var update=new LinkedHashMap<String,Object>(Map.of("leaseToken",token,"expectedStatus",stages[i],"status",stages[i+1]));
                if(stages[i+1].equals("DEPLOYING"))update.put("imageReference","registry.example/shipora/web@sha256:"+"a".repeat(64));
                var response=send(http,"PUT",jobPath+"/status",update,"worker",null,200);assertEquals(stages[i+1],response.path("status").asText());
                assertEquals(response.path("version").asLong(),send(http,"PUT",jobPath+"/status",update,"worker",null,200).path("version").asLong());
            }
            send(http,"POST",jobPath+"/heartbeats",Map.of("leaseToken",token),"worker",null,409);
            send(http,"POST",jobPath+"/events",processOutput,"worker",null,409);
            var events=send(http,"GET","/api/deployments/"+id+"/events",null,"api",null,200);assertEquals(8,events.size());assertFalse(events.toString().contains(token));assertTrue(events.toString().contains("PROCESS_OUTPUT"));
            var releases=send(http,"GET","/api/applications/"+APP+"/releases",null,"api",null,200);assertEquals(1,releases.size());assertEquals("ACTIVE",releases.get(0).path("status").asText());
            send(http,"POST","/api/deployments",body,"api","request-2",202);
            var failed=send(http,"POST","/internal/deployment-jobs/claims",null,"worker",null,200);String failedId=failed.path("deployment").path("id").asText();
            send(http,"PUT","/internal/deployment-jobs/"+failed.path("jobId").asText()+"/status",
                Map.of("leaseToken",failed.path("leaseToken").asText(),"expectedStatus","PREPARING","status","FAILED","failureCode","SOURCE_UNAVAILABLE"),"worker",null,200);
            var retry=send(http,"POST","/api/deployments/"+failedId+"/retries",null,"api","retry-1",202);assertEquals(failedId,retry.path("retryOf").asText());
            send(http,"POST","/api/deployments/"+retry.path("id").asText()+"/cancellations",Map.of("version",0),"api",null,200);
            send(http,"POST","/internal/deployment-jobs/claims",null,"worker",null,204);
            var history=send(http,"GET","/api/deployments?applicationId="+APP,null,"api",null,200);assertEquals(3,history.size());assertFalse(history.toString().contains("leaseToken"));
        }
    }
    static String auth(String role){return "Basic "+Base64.getEncoder().encodeToString((role.equals("api")?"deployment-test:test-only":"worker-test:worker-test-only").getBytes(StandardCharsets.UTF_8));}
    JsonNode send(HttpClient http,String method,String path,Object body,String role,String key,int expected)throws Exception{
        int port=environment.getProperty("local.server.port",Integer.class);var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(role!=null)request.header("Authorization",auth(role));if(key!=null)request.header("Idempotency-Key",key);if(body!=null)request.header("Content-Type","application/json");
        var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),method+" "+path+" "+response.body());return response.body().isEmpty()?JSON.createObjectNode():JSON.readTree(response.body());
    }
}
