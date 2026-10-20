package com.shipora.application;

import com.shipora.application.application.port.out.*;
import com.shipora.application.domain.KubernetesAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "shipora.local.username=kube-test","shipora.local.password=test-only",
        "shipora.local.organization-id=22222222-2222-2222-2222-222222222222"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="APPLICATION_BOOT_TEST_JDBC_URL",matches=".+")
class KubernetesApplicationBootTest {
    @TempDir static Path directory;
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KubernetesProbe probe;
    @MockitoBean ResourceCatalog catalog;
    final JsonMapper json=JsonMapper.builder().build();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){
        String url=System.getenv("APPLICATION_BOOT_TEST_JDBC_URL");
        if(!url.matches("jdbc:postgresql://[^/]+/shipora_application_test_[a-z0-9_]+"))throw new IllegalArgumentException("Use a disposable database.");
        properties.add("spring.datasource.url",()->url+"?currentSchema=application");
        properties.add("spring.datasource.username",()->System.getenv().getOrDefault("APPLICATION_TEST_DB_USER","postgres"));
        properties.add("spring.datasource.password",()->System.getenv().getOrDefault("APPLICATION_TEST_DB_PASSWORD",""));
        properties.add("shipora.secrets.data-directory",()->directory.toString());
    }
    @Test void authenticatedHttpSupportsRegistrationVerificationApplicationSelectionRotationAndCleanup()throws Exception{
        // This fixture owns the explicitly disposable boot database. Reset the
        // fingerprint with its data: each run uses a fresh temporary key directory.
        jdbc.execute("TRUNCATE TABLE application.applications, application.kubernetes_targets, application.secret_key_registry CASCADE");
        when(probe.check(any())).thenReturn(new KubernetesProbe.Result(true,KubernetesProbe.Code.OK));
        when(catalog.requireRepository(any(),any())).thenReturn("main");
        try(var http=HttpClient.newHttpClient()){
            var created=send(http,"POST","/api/kubernetes-targets",Map.of("name","Cluster-"+UUID.randomUUID(),"apiServer","https://cluster",
                    "namespace","shipora","token","first-private-token"),201);
            String target=created.path("id").asText();String path="/api/kubernetes-targets/"+target;
            assertEquals("UNVERIFIED",created.path("status").asText());assertFalse(created.has("credentials"));assertFalse(created.has("token"));
            String stored=jdbc.queryForObject("SELECT encrypted_credentials FROM application.kubernetes_targets WHERE id=?",String.class,UUID.fromString(target));
            assertNotNull(stored);assertFalse(stored.contains("first-private-token"));
            var body=new LinkedHashMap<String,Object>(Map.of("projectId",UUID.randomUUID(),"repositoryId",UUID.randomUUID(),"runtimeType","KUBERNETES",
                    "kubernetesTargetId",target,"name","Web","slug","web","containerPort",3000));
            send(http,"POST","/api/applications",body,409);
            var checked=send(http,"POST",path+"/connection-checks",Map.of("version",0),200);
            assertEquals("ACTIVE",checked.path("status").asText());assertEquals(1,checked.path("version").asLong());
            var app=send(http,"POST","/api/applications",body,201);String appPath="/api/applications/"+app.path("id").asText();
            assertEquals("KUBERNETES",app.path("runtimeType").asText());assertTrue(app.path("serverId").isNull());
            send(http,"DELETE",path+"?version=1",null,409);
            var rotated=send(http,"PATCH",path,Map.of("version",1,"token","second-private-token"),200);
            assertEquals("UNVERIFIED",rotated.path("status").asText());assertTrue(rotated.path("checkedAt").isNull());
            send(http,"POST",path+"/connection-checks",Map.of("version",1),409);
            send(http,"PATCH",appPath,Map.of("version",0,"name","Renamed"),409);
            send(http,"POST",path+"/connection-checks",Map.of("version",2),200);
            verify(probe).check(new KubernetesAccess("https://cluster","shipora","second-private-token",""));
            var moved=send(http,"PATCH",appPath,Map.of("version",0,"runtimeType","DOCKER","serverId",UUID.randomUUID()),200);
            assertEquals("DOCKER",moved.path("runtimeType").asText());assertTrue(moved.path("kubernetesTargetId").isNull());
            send(http,"DELETE",path+"?version=3",null,204);send(http,"GET",path,null,404);
            assertNull(jdbc.queryForObject("SELECT encrypted_credentials FROM application.kubernetes_targets WHERE id=?",String.class,UUID.fromString(target)));
            send(http,"DELETE",appPath+"?version=1",null,204);
        }
    }
    JsonNode send(HttpClient http,String method,String path,Object body,int expected)throws Exception{
        String authorization="Basic "+Base64.getEncoder().encodeToString("kube-test:test-only".getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization",authorization);
        if(body!=null)request.header("Content-Type","application/json");
        var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),method+" "+path+" "+response.body());
        assertFalse(response.body().contains("first-private-token"));assertFalse(response.body().contains("second-private-token"));
        return response.body().isEmpty()?json.createObjectNode():json.readTree(response.body());
    }
}
