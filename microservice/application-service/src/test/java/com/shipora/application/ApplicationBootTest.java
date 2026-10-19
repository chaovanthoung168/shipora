package com.shipora.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "shipora.local.username=boot-test","shipora.local.password=test-only",
        "shipora.local.organization-id=11111111-1111-1111-1111-111111111111"})
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named="APPLICATION_BOOT_TEST_JDBC_URL",matches=".+")
class ApplicationBootTest {
    @Value("${local.server.port}") int port;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){
        String url=System.getenv("APPLICATION_BOOT_TEST_JDBC_URL");
        if(!url.matches("jdbc:postgresql://[^/]+/shipora_application_test_[a-z0-9_]+"))
            throw new IllegalArgumentException("Use a separate disposable boot-test database.");
        properties.add("spring.datasource.url",()->url+"?currentSchema=application");
        properties.add("spring.datasource.username",()->System.getenv().getOrDefault("APPLICATION_TEST_DB_USER","postgres"));
        properties.add("spring.datasource.password",()->System.getenv().getOrDefault("APPLICATION_TEST_DB_PASSWORD",""));
    }
    @Test void fullProductionBeanGraphStartsAndServesAuthenticatedHttp()throws Exception{
        try(var http=HttpClient.newHttpClient()){
            var uri=URI.create("http://127.0.0.1:"+port+"/api/applications");
            assertEquals(401,http.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            String auth="Basic "+Base64.getEncoder().encodeToString("boot-test:test-only".getBytes(StandardCharsets.UTF_8));
            var response=http.send(HttpRequest.newBuilder(uri).header("Authorization",auth).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertEquals("[]",response.body());
        }
    }
}
