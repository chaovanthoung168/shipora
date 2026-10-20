package com.shipora.application.adapter.out.http;

import com.shipora.application.application.port.out.KubernetesProbe;
import com.shipora.application.domain.KubernetesAccess;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpKubernetesProbeTest {
    @TempDir static Path directory;
    static SSLContext tls;static String ca;
    HttpsServer server;ExecutorService executor;
    final AtomicReference<String> mode=new AtomicReference<>("ok");
    final List<String> requests=new CopyOnWriteArrayList<>();
    final List<Map<String,String>> reviews=new CopyOnWriteArrayList<>();
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeAll static void certificate()throws Exception {
        Path file=directory.resolve("server.p12");
        String executable=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"keytool.exe":"keytool";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-genkeypair","-alias","server",
                "-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=localhost","-ext","SAN=dns:localhost",
                "-keystore",file.toString(),"-storetype","PKCS12","-storepass","test-only-password","-noprompt").redirectErrorStream(true).start();
        assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(file)){keys.load(input,"test-only-password".toCharArray());}
        var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,"test-only-password".toCharArray());
        tls=SSLContext.getInstance("TLS");tls.init(managers.getKeyManagers(),null,null);
        String pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(keys.getCertificate("server").getEncoded())+"\n-----END CERTIFICATE-----\n";
        ca=Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
    }
    @BeforeEach void start()throws Exception {
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));
        executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);server.createContext("/",this::respond);server.start();
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    void respond(HttpExchange exchange)throws IOException {
        try(exchange) {
            String path=exchange.getRequestURI().getPath();requests.add(exchange.getRequestMethod()+" "+path);
            if(!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))){send(exchange,401,"{}");return;}
            String behavior=mode.get();
            if(behavior.equals("timeout")){try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}send(exchange,200,"{}");return;}
            if(behavior.equals("redirect")){exchange.getResponseHeaders().set("Location","https://localhost:"+server.getAddress().getPort()+"/redirected");send(exchange,302,"{}");return;}
            if(behavior.equals("401") || behavior.equals("403") || behavior.equals("404")){send(exchange,Integer.parseInt(behavior),"{\"message\":\"private-token\"}");return;}
            if(behavior.equals("large")){send(exchange,200,"x".repeat(150000));return;}
            if(behavior.equals("malformed")){send(exchange,200,"invalid-json");return;}
            if(path.equals("/api/v1/namespaces/shipora")) {
                send(exchange,200,"{\"kind\":\"Namespace\",\"metadata\":{\"name\":\"shipora\"},\"status\":{\"phase\":\"Active\"}}");return;
            }
            if(path.equals("/apis/authorization.k8s.io/v1/selfsubjectaccessreviews")) {
                var tree=json.readTree(exchange.getRequestBody().readAllBytes());
                assertEquals("SelfSubjectAccessReview",tree.path("kind").asText());assertEquals("authorization.k8s.io/v1",tree.path("apiVersion").asText());
                var attributes=tree.path("spec").path("resourceAttributes");
                var entry=new HashMap<String,String>();for(String field:List.of("namespace","resource","group","verb","subresource"))
                    if(attributes.has(field))entry.put(field,attributes.path(field).asText());reviews.add(entry);
                String allowed=behavior.equals("deny")?"false":behavior.equals("nonBoolean")?"\"true\"":"true";
                send(exchange,201,"{\"kind\":\"SelfSubjectAccessReview\",\"status\":{\"allowed\":"+allowed+"}}");return;
            }
            send(exchange,500,"{}");
        }
    }
    void send(HttpExchange exchange,int status,String body)throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    KubernetesAccess access(String hostname,String certificate){return new KubernetesAccess("https://"+hostname+":"+server.getAddress().getPort(),"shipora","test-token",certificate);}
    @Test void validatesTlsNamespaceAndEveryRequiredPermissionWithoutCreatingWorkloads() {
        assertEquals(new KubernetesProbe.Result(true,KubernetesProbe.Code.OK),new HttpKubernetesProbe().check(access("localhost",ca)));
        assertEquals(HttpKubernetesProbe.permissions().size(),reviews.size());
        for(var permission:HttpKubernetesProbe.permissions())assertTrue(reviews.stream().anyMatch(r->r.get("namespace").equals("shipora")
                &&r.get("group").equals(permission.group())&&r.get("resource").equals(permission.resource())&&r.get("verb").equals(permission.verb())
                &&Objects.equals(r.get("subresource"),permission.subresource())));
        assertEquals(1+reviews.size(),requests.size());assertEquals("GET /api/v1/namespaces/shipora",requests.getFirst());
        assertTrue(requests.stream().skip(1).allMatch(r->r.equals("POST /apis/authorization.k8s.io/v1/selfsubjectaccessreviews")));
    }
    @Test void rejectsUntrustedCertificatesHostnameMismatchAndInvalidCaBeforeSendingCredentials() {
        assertEquals(KubernetesProbe.Code.TLS_FAILED,new HttpKubernetesProbe().check(access("localhost","")).code());
        assertEquals(KubernetesProbe.Code.TLS_FAILED,new HttpKubernetesProbe().check(access("127.0.0.1",ca)).code());
        assertEquals(KubernetesProbe.Code.INVALID_CERTIFICATE,new HttpKubernetesProbe().check(access("localhost","Y2E=")).code());assertTrue(requests.isEmpty());
    }
    @Test void mapsAuthenticationNamespaceAndRbacFailuresToSafeCodes() {
        for(var entry:Map.of("401",KubernetesProbe.Code.AUTHENTICATION_FAILED,"403",KubernetesProbe.Code.ACCESS_DENIED,"404",KubernetesProbe.Code.NAMESPACE_NOT_FOUND,"deny",KubernetesProbe.Code.ACCESS_DENIED).entrySet()) {
            mode.set(entry.getKey());var result=new HttpKubernetesProbe().check(access("localhost",ca));assertFalse(result.allowed());assertEquals(entry.getValue(),result.code());assertFalse(result.toString().contains("private-token"));
        }
    }
    @Test void rejectsRedirectsMalformedAndNonBooleanResponses() {
        for(String behavior:List.of("redirect","malformed","nonBoolean")){mode.set(behavior);assertEquals(KubernetesProbe.Code.INVALID_RESPONSE,new HttpKubernetesProbe().check(access("localhost",ca)).code());}
        assertFalse(requests.stream().anyMatch(r->r.contains("redirected")));
    }
    @Test void boundsResponseSizeAndTotalTime() {
        mode.set("large");assertFalse(new HttpKubernetesProbe().check(access("localhost",ca)).allowed());
        mode.set("timeout");long start=System.nanoTime();assertFalse(new HttpKubernetesProbe(Duration.ofMillis(300)).check(access("localhost",ca)).allowed());
        assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(2))<0);
    }
}
