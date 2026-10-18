package com.shipora.git.adapter.out.github;

import com.shipora.git.configuration.GitSetupProperties;
import com.shipora.git.domain.GitException;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GitHubManifestTest {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void generatesInstanceUrlsAndMinimalReadPermissions() {
        var result=manifest("https://customer.example",false).create("Shipora-test"); var data=json.readTree(result.json());
        assertEquals("https://customer.example/api/git/setup/github/callback",data.path("redirect_url").asText());
        assertEquals("https://customer.example/api/git/github/callback",result.authorizationCallback().toString());
        assertEquals("read",data.path("default_permissions").path("contents").asText());
        assertEquals("read",data.path("default_permissions").path("members").asText());
        assertFalse(data.path("hook_attributes").path("active").asBoolean()); assertTrue(data.path("public").asBoolean());
    }
    @Test void rejectsInsecureRemoteOrAmbiguousOrigins() {
        for(String value:new String[]{"http://customer.example","https://user:password@example.com","https://customer.example?x=y","https://customer.example/prefix"})
            assertThrows(GitException.class,()->manifest(value,false).create("test"));
    }
    @Test void localhostWorksWithoutWebhookDeliveryButCannotEnableIt() {
        assertNotNull(manifest("http://localhost:8084",false).create("test"));
        assertThrows(GitException.class,()->manifest("http://localhost:8084",true).create("test"));
    }
    @Test void nextJsCallbackUrlsCanBeConfiguredByAdministrator() {
        var builder=new GitHubManifest(new GitSetupProperties(URI.create("https://customer.example"),
                URI.create("https://customer.example/api/github/setup/callback"),URI.create("https://customer.example/api/github/callback"),Path.of("data"),true));
        var data=json.readTree(builder.create("test").json());
        assertEquals("https://customer.example/api/github/setup/callback",data.path("redirect_url").asText());
        assertTrue(data.path("hook_attributes").path("active").asBoolean());
    }
    GitHubManifest manifest(String url,boolean hooks){return new GitHubManifest(new GitSetupProperties(URI.create(url),null,null,Path.of("data"),hooks));}
}
