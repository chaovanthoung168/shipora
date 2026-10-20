package com.shipora.application.adapter.out.http;

import com.shipora.application.application.port.out.KubernetesProbe;
import com.shipora.application.domain.KubernetesAccess;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class HttpKubernetesProbe implements KubernetesProbe {
    private final JsonMapper json = JsonMapper.builder().build();
    private final Duration budget;
    public HttpKubernetesProbe() { this(Duration.ofSeconds(20)); }
    HttpKubernetesProbe(Duration budget) { this.budget=budget; }
    public record Permission(String group, String resource, String verb, String subresource) {}
    public static List<Permission> permissions() {
        var values = new ArrayList<Permission>();
        for(String verb:List.of("get","list","watch","create","patch","delete")) values.add(new Permission("apps","deployments",verb,null));
        for(String resource:List.of("services","configmaps","secrets"))
            for(String verb:List.of("get","create","patch","delete")) values.add(new Permission("",resource,verb,null));
        for(String verb:List.of("get","list","watch")) values.add(new Permission("","pods",verb,null));
        for(String verb:List.of("get","create","patch","delete")) values.add(new Permission("networking.k8s.io","ingresses",verb,null));
        values.add(new Permission("","pods","get","log"));
        return List.copyOf(values);
    }
    @Override public Result check(KubernetesAccess access) {
        SSLContext tls;
        try { tls = context(access.certificateAuthorityData()); }
        catch(Exception e) { return failure(Code.INVALID_CERTIFICATE); }
        long deadline = System.nanoTime()+budget.toNanos();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).sslContext(tls).build();
        try {
            var namespace = send(http,access,"/api/v1/namespaces/"+access.namespace(),null,deadline);
            checkStatus(namespace.statusCode(),true);
            var document = json.readTree(namespace.body());
            if(document==null || !"Namespace".equals(document.path("kind").asText())
                    || !access.namespace().equals(document.path("metadata").path("name").asText())
                    || !"Active".equals(document.path("status").path("phase").asText())) throw new Failed(Code.INVALID_RESPONSE);
            for(var permission:permissions()) {
                var attributes = new LinkedHashMap<String,String>();
                attributes.put("namespace",access.namespace()); attributes.put("group",permission.group());
                attributes.put("resource",permission.resource()); attributes.put("verb",permission.verb());
                if(permission.subresource()!=null) attributes.put("subresource",permission.subresource());
                String body = json.writeValueAsString(Map.of("apiVersion","authorization.k8s.io/v1","kind","SelfSubjectAccessReview",
                        "spec",Map.of("resourceAttributes",attributes)));
                var response = send(http,access,"/apis/authorization.k8s.io/v1/selfsubjectaccessreviews",body,deadline);
                checkStatus(response.statusCode(),false);
                JsonNode review = json.readTree(response.body());
                if(review==null || !"SelfSubjectAccessReview".equals(review.path("kind").asText())
                        || !review.path("status").path("allowed").isBoolean()) throw new Failed(Code.INVALID_RESPONSE);
                if(!review.path("status").path("allowed").asBoolean()
                        || !review.path("status").path("evaluationError").asText("").isEmpty()) throw new Failed(Code.ACCESS_DENIED);
            }
            return new Result(true,Code.OK);
        } catch(Failed e) { return failure(e.code); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); return failure(Code.UNREACHABLE); }
        catch(Exception e) {
            for(Throwable cause=e;cause!=null;cause=cause.getCause()) if(cause instanceof SSLException) return failure(Code.TLS_FAILED);
            return failure(e instanceof tools.jackson.core.JacksonException?Code.INVALID_RESPONSE:Code.UNREACHABLE);
        } finally { http.shutdownNow(); }
    }
    private HttpResponse<byte[]> send(HttpClient http,KubernetesAccess access,String path,String body,long deadline)throws Exception {
        long remaining = deadline-System.nanoTime();
        if(remaining<=0) throw new TimeoutException();
        var request = HttpRequest.newBuilder(URI.create(access.apiServer()+path)).timeout(Duration.ofNanos(remaining))
                .header("Accept","application/json").header("Authorization","Bearer "+access.token());
        if(body==null) request.GET();
        else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        var pending=http.sendAsync(request.build(),ignored->new LimitedBodySubscriber(131072));
        try { return pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
        finally { if(!pending.isDone()) pending.cancel(true); }
    }
    private static void checkStatus(int status,boolean namespace) {
        if(status==401) throw new Failed(Code.AUTHENTICATION_FAILED);
        if(status==403) throw new Failed(Code.ACCESS_DENIED);
        if(namespace && status==404) throw new Failed(Code.NAMESPACE_NOT_FOUND);
        if(status!=200 && !(status==201 && !namespace)) throw new Failed(Code.INVALID_RESPONSE);
    }
    private static SSLContext context(String data)throws Exception {
        if(data.isEmpty()) return SSLContext.getDefault();
        var certificates = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
        if(certificates.isEmpty()) throw new IllegalArgumentException();
        var keys = KeyStore.getInstance(KeyStore.getDefaultType()); keys.load(null,null);
        int index=0; for(var certificate:certificates) keys.setCertificateEntry("ca-"+(index++),certificate);
        var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(keys);
        var context=SSLContext.getInstance("TLS"); context.init(null,managers.getTrustManagers(),null); return context;
    }
    private static Result failure(Code code) { return new Result(false,code); }
    private static final class Failed extends RuntimeException {
        private final Code code;
        Failed(Code code) { super(code.name()); this.code=code; }
    }
}
