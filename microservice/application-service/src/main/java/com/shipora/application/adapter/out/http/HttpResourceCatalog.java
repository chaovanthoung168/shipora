package com.shipora.application.adapter.out.http;

import com.shipora.application.application.port.out.ResourceCatalog;
import com.shipora.application.configuration.CatalogProperties;
import com.shipora.application.domain.ApplicationException;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static com.shipora.application.domain.ApplicationException.Kind.*;

public final class HttpResourceCatalog implements ResourceCatalog,AutoCloseable {
    private final CatalogProperties properties;
    private final Function<UUID,String> authorization;
    private final HttpClient http;
    private final JsonMapper json=JsonMapper.builder().build();
    private final Semaphore slots=new Semaphore(8);
    public HttpResourceCatalog(CatalogProperties properties,Function<UUID,String> authorization){
        this(properties,authorization,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    HttpResourceCatalog(CatalogProperties properties,Function<UUID,String> authorization,HttpClient http){
        validate(properties.projectUrl());validate(properties.gitUrl());
        this.properties=properties;this.authorization=authorization;this.http=http;
    }
    @Override public void requireProject(UUID org,UUID id){
        var result=get(properties.projectUrl(),"/api/projects/"+id,org); identity(result,id,org);
    }
    @Override public String requireRepository(UUID org,UUID id){
        var result=get(properties.gitUrl(),"/api/git/repositories/"+id,org);
        if(!id.toString().equals(result.path("id").asText()))throw unavailable();
        if(!result.path("available").isBoolean())throw unavailable();
        if(!result.path("available").asBoolean())throw new ApplicationException(CONFLICT,"Repository is unavailable. Reconnect GitHub and synchronize.");
        String branch=result.path("defaultBranch").asText("");if(branch.isBlank())throw unavailable();return branch;
    }
    @Override public void requireActiveServer(UUID org,UUID id){
        var result=get(properties.projectUrl(),"/api/servers/"+id,org);identity(result,id,org);
        String state=result.path("status").asText("");
        if(!Set.of("ACTIVE","OFFLINE","ERROR").contains(state))throw unavailable();
        if(!"ACTIVE".equals(state))throw new ApplicationException(CONFLICT,"Server is not active. Run its connection check first.");
    }
    private void identity(JsonNode result,UUID id,UUID org){
        if(!id.toString().equals(result.path("id").asText()) || !org.toString().equals(result.path("organizationId").asText()))
            throw new ApplicationException(NOT_FOUND,"Referenced resource not found.");
    }
    private JsonNode get(URI origin,String path,UUID org){
        // Resolve the caller before catch/translation: authentication failures must remain authorization failures.
        String credentials=authorization.apply(org);
        if(!slots.tryAcquire())throw new ApplicationException(DEPENDENCY,"Catalog validation is busy. Retry shortly.");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            var request=HttpRequest.newBuilder(URI.create(origin.toString().replaceAll("/+$","")+path))
                    .timeout(Duration.ofSeconds(8)).header("Accept","application/json").header("Authorization",credentials).GET().build();
            pending=http.sendAsync(request,ignored->new LimitedBodySubscriber(262144));
            var response=pending.get(10,TimeUnit.SECONDS);
            if(response.statusCode()==404)throw new ApplicationException(NOT_FOUND,"Referenced resource not found.");
            if(response.statusCode()==401 || response.statusCode()==403)throw new ApplicationException(FORBIDDEN,"The caller cannot access a referenced resource.");
            if(response.statusCode()!=200)throw unavailable();
            var result=json.readTree(response.body());if(result==null || !result.isObject())throw unavailable();return result;
        }catch(ApplicationException e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception e){throw unavailable();}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);slots.release();}
    }
    private static void validate(URI uri){
        if(uri==null || uri.getHost()==null || !Set.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw new IllegalArgumentException("Catalog URLs must be trusted HTTP(S) origins without credentials, paths or query strings.");
    }
    private static ApplicationException unavailable(){return new ApplicationException(DEPENDENCY,"A catalog service is unavailable or returned an invalid response.");}
    @Override public void close(){http.close();}
}
