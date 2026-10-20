package com.shipora.deployment.adapter.out.http;

import com.shipora.deployment.application.port.out.DeploymentCatalog;
import com.shipora.deployment.configuration.CatalogProperties;
import com.shipora.deployment.domain.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import static com.shipora.deployment.domain.DeploymentException.Kind.*;

public final class HttpDeploymentCatalog implements DeploymentCatalog,AutoCloseable {
    private final CatalogProperties properties;
    private final Function<UUID,String> authorization;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient http;
    private final Semaphore slots=new Semaphore(8);
    public HttpDeploymentCatalog(CatalogProperties properties,Function<UUID,String> authorization){
        validate(properties.applicationUrl());validate(properties.projectUrl());validate(properties.gitUrl());
        this.properties=properties;this.authorization=authorization;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public ApplicationSnapshot requireReadyApplication(UUID org,UUID appId){
        String credentials=authorization.apply(org);
        var snapshot=configuration(org,appId,credentials);
        return requireReady(snapshot,org,credentials);
    }
    public ApplicationSnapshot configuration(UUID org,UUID appId,String credentials){
        var app=get(properties.applicationUrl(),"/api/applications/"+appId,credentials);identity(app,org,appId);
        ApplicationSnapshot snapshot;
        try {
            if(!app.path("version").isIntegralNumber() || app.path("version").asLong()<0 || !app.path("containerPort").isIntegralNumber())throw unavailable();
            snapshot=new ApplicationSnapshot(appId,org,uuid(app,"projectId"),uuid(app,"repositoryId"),text(app,"runtimeType"),nullableUuid(app,"serverId"),
                    nullableUuid(app,"kubernetesTargetId"),text(app,"name"),text(app,"slug"),text(app,"branch"),text(app,"dockerfilePath"),text(app,"buildContext"),
                    app.path("containerPort").asInt(),text(app,"healthPath"),app.path("version").asLong(),app.path("autoDeploy").asBoolean(false));
            if(snapshot.containerPort()<1 || snapshot.containerPort()>65535)throw unavailable();
        }catch(RuntimeException e){throw unavailable();}
        return snapshot;
    }
    private ApplicationSnapshot requireReady(ApplicationSnapshot snapshot,UUID org,String credentials){
        identity(get(properties.projectUrl(),"/api/projects/"+snapshot.projectId(),credentials),org,snapshot.projectId());
        var repo=get(properties.gitUrl(),"/api/git/repositories/"+snapshot.repositoryId(),credentials);
        if(!snapshot.repositoryId().toString().equals(repo.path("id").asText()) || !repo.path("available").isBoolean())throw unavailable();
        if(!repo.path("available").asBoolean())throw new DeploymentException(CONFLICT,"Repository is unavailable. Reconnect and synchronize GitHub.");
        if(snapshot.runtimeType().equals("DOCKER") && snapshot.serverId()!=null && snapshot.kubernetesTargetId()==null){
            var server=get(properties.projectUrl(),"/api/servers/"+snapshot.serverId(),credentials);identity(server,org,snapshot.serverId());active(server);
        }else if(snapshot.runtimeType().equals("KUBERNETES") && snapshot.kubernetesTargetId()!=null && snapshot.serverId()==null){
            var target=get(properties.applicationUrl(),"/api/kubernetes-targets/"+snapshot.kubernetesTargetId(),credentials);identity(target,org,snapshot.kubernetesTargetId());active(target);
        }else throw unavailable();
        return snapshot;
    }
    private void active(JsonNode resource){
        String state=resource.path("status").asText("");
        if(!Set.of("ACTIVE","UNVERIFIED","OFFLINE","ERROR").contains(state))throw unavailable();
        if(!state.equals("ACTIVE"))throw new DeploymentException(CONFLICT,"Deployment target is not ACTIVE. Run its connection check first.");
    }
    private JsonNode get(URI origin,String path,String credentials){
        if(!slots.tryAcquire())throw new DeploymentException(UNAVAILABLE,"Catalog validation is busy. Retry later.");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            var request=HttpRequest.newBuilder(URI.create(origin.toString().replaceAll("/+$","")+path)).timeout(Duration.ofSeconds(8))
                    .header("Accept","application/json").header("Authorization",credentials).GET().build();
            pending=http.sendAsync(request,ignored->new LimitedBodySubscriber(262144));
            var response=pending.get(10,TimeUnit.SECONDS);
            if(response.statusCode()==404)throw new DeploymentException(NOT_FOUND,"Referenced application or resource not found.");
            if(response.statusCode()==401 || response.statusCode()==403)throw new DeploymentException(FORBIDDEN,"The caller cannot access a referenced resource.");
            if(response.statusCode()!=200)throw unavailable();
            var result=json.readTree(response.body());if(result==null || !result.isObject())throw unavailable();return result;
        }catch(DeploymentException e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception e){throw unavailable();}
        finally {if(pending!=null&&!pending.isDone())pending.cancel(true);slots.release();}
    }
    private static void identity(JsonNode value,UUID org,UUID id){
        if(!id.toString().equals(value.path("id").asText()) || !org.toString().equals(value.path("organizationId").asText()))
            throw new DeploymentException(NOT_FOUND,"Referenced application or resource not found.");
    }
    private static String text(JsonNode node,String key){var value=node.path(key);if(!value.isString() || value.asText().isBlank() || value.asText().length()>255)throw unavailable();return value.asText();}
    private static UUID uuid(JsonNode node,String key){return UUID.fromString(text(node,key));}
    private static UUID nullableUuid(JsonNode node,String key){return node.path(key).isNull()?null:uuid(node,key);}
    private static void validate(URI origin){
        if(origin==null || origin.getHost()==null || !Set.of("http","https").contains(origin.getScheme()) || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null
                || origin.getRawFragment()!=null || !(origin.getPath().isEmpty()||origin.getPath().equals("/")))throw new IllegalArgumentException("Catalog URLs must be trusted HTTP(S) origins.");
    }
    private static DeploymentException unavailable(){return new DeploymentException(UNAVAILABLE,"A catalog service is unavailable or returned an invalid response.");}
    @Override public void close(){http.shutdownNow();}
}
