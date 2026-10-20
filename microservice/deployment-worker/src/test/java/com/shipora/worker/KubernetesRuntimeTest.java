package com.shipora.worker;
import com.shipora.pipeline.Protocol.*;
import com.shipora.worker.adapter.out.kubernetes.KubernetesRuntime;
import com.shipora.worker.domain.ExecutionFailure;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class KubernetesRuntimeTest {
 @TempDir static Path certificates;@TempDir Path directory;
 static SSLContext tls;static String ca;
 final JsonMapper json=JsonMapper.builder().build();final Map<String,JsonNode> resources=new ConcurrentHashMap<>();
 final AtomicLong versions=new AtomicLong();final AtomicInteger writes=new AtomicInteger(),requests=new AtomicInteger();
 HttpsServer server;ExecutorService executor;boolean healthy=true,race=false;
 @BeforeAll static void certificates()throws Exception{
  String executable=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"keytool.exe":"keytool";
  Path file=certificates.resolve("server.p12");
  var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-genkeypair","-alias","server","-keyalg","RSA","-keysize","2048","-validity","2","-dname","CN=localhost","-ext","SAN=dns:localhost","-keystore",file.toString(),"-storetype","PKCS12","-storepass","test-only-password","-noprompt").redirectErrorStream(true).start();
  assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
  var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(file)){keys.load(input,"test-only-password".toCharArray());}
  var manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());manager.init(keys,"test-only-password".toCharArray());
  tls=SSLContext.getInstance("TLS");tls.init(manager.getKeyManagers(),null,null);
  ca=Base64.getEncoder().encodeToString(keys.getCertificate("server").getEncoded());
 }
 @BeforeEach void setup()throws Exception{
  server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
  server.createContext("/",exchange->{
   try(exchange){
    requests.incrementAndGet();if(!"Bearer kube-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))){send(exchange,401,json.createObjectNode());return;}
    String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
    if(method.equals("GET")){var found=resources.get(path);send(exchange,found==null?404:200,found==null?json.createObjectNode():found);return;}
    var incoming=(ObjectNode)json.readTree(exchange.getRequestBody().readAllBytes());
    String actual=method.equals("POST")?path+"/"+incoming.path("metadata").path("name").asText():path;
    var existing=resources.get(actual);
    if(method.equals("DELETE")){
     if(existing==null){send(exchange,404,json.createObjectNode());return;}
     assertEquals(existing.path("metadata").path("uid").asText(),incoming.path("preconditions").path("uid").asText());
     assertEquals(existing.path("metadata").path("resourceVersion").asText(),incoming.path("preconditions").path("resourceVersion").asText());
     resources.remove(actual);writes.incrementAndGet();send(exchange,200,json.createObjectNode());return;
    }
    if(method.equals("PATCH")&&race&&path.contains("/deployments/")){
     race=false;var newer=(ObjectNode)existing.deepCopy();((ObjectNode)newer.path("metadata").path("annotations")).put("shipora.io/fence","999");
     ((ObjectNode)newer.path("metadata")).put("resourceVersion",Long.toString(versions.incrementAndGet()));resources.put(path,newer);send(exchange,409,json.createObjectNode());return;
    }
    if(existing!=null&&(!method.equals("PATCH")||!existing.path("metadata").path("resourceVersion").asText().equals(incoming.path("metadata").path("resourceVersion").asText()))){send(exchange,409,json.createObjectNode());return;}
    ObjectNode meta=(ObjectNode)incoming.path("metadata");meta.put("resourceVersion",Long.toString(versions.incrementAndGet()));meta.put("uid",existing==null?UUID.randomUUID().toString():existing.path("metadata").path("uid").asText());meta.put("generation",1);
    if(path.contains("/deployments"))incoming.set("status",json.valueToTree(Map.of("observedGeneration",1,"updatedReplicas",healthy?1:0,"availableReplicas",healthy?1:0,"replicas",1,"unavailableReplicas",healthy?0:1)));
    resources.put(actual,incoming);writes.incrementAndGet();send(exchange,method.equals("POST")?201:200,incoming);
   }
  });server.start();
 }
 @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
 void send(HttpExchange exchange,int status,JsonNode body)throws java.io.IOException{byte[] bytes=json.writeValueAsBytes(body);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}
 ApplicationContext context(String trust){return new ApplicationContext(3,Map.of("SECRET","line1\nline2"),new KubernetesConnection("https://localhost:"+server.getAddress().getPort(),"shipora-apps","kube-test-token",trust));}
 String path(){return "/apis/apps/v1/namespaces/shipora-apps/deployments/shipora-"+Fixtures.APP;}
 Job next(Job old){var d=old.deployment();return new Job(UUID.randomUUID(),UUID.randomUUID(),old.leaseExpiresAt(),1,new DeploymentInfo(UUID.randomUUID(),d.organizationId(),d.applicationId(),d.commitSha(),d.branch(),d.triggerType(),d.status(),d.configuration()),old.fence()+1);}
 @Test void deploysDigestScopedSecretsReadinessAndClusterServiceWithoutDisablingTls(){
  var job=Fixtures.job("KUBERNETES","MANUAL");var runtime=new KubernetesRuntime(Fixtures.properties(directory));var handle=runtime.deploy(job,context(ca),null,Fixtures.IMAGE,()->{});
  String endpoint=runtime.verify(handle,()->{});assertTrue(endpoint.contains(".shipora-apps.svc.cluster.local"));
  var deployment=resources.get(path());assertEquals(Fixtures.IMAGE,deployment.path("spec").path("template").path("spec").path("containers").get(0).path("image").asText());
  assertFalse(deployment.path("spec").path("template").path("spec").path("automountServiceAccountToken").asBoolean(true));assertFalse(deployment.toString().contains("line1"));
  assertTrue(resources.values().stream().anyMatch(v->v.path("stringData").path("SECRET").asText().equals("line1\nline2")));assertFalse(handle.toString().contains("kube-test-token"));
 }
 @Test void failedRolloutRestoresPreviousSpecWithoutDecreasingFence(){
  var runtime=new KubernetesRuntime(Fixtures.properties(directory));var first=Fixtures.job("KUBERNETES","MANUAL");runtime.verify(runtime.deploy(first,context(ca),null,Fixtures.IMAGE,()->{}),()->{});
  var second=next(first);String image="registry.example/shipora/image@sha256:"+"c".repeat(64);healthy=false;
  var handle=runtime.deploy(second,context(ca),null,image,()->{});assertThrows(ExecutionFailure.class,()->runtime.verify(handle,()->{}));runtime.rollback(handle,()->{});
  assertEquals(Fixtures.IMAGE,resources.get(path()).path("spec").path("template").path("spec").path("containers").get(0).path("image").asText());
  assertEquals(second.fence(),resources.get(path()).path("metadata").path("annotations").path("shipora.io/fence").asLong());
 }
 @Test void newerFenceWinsAConcurrentResourceVersionConflict(){
  var runtime=new KubernetesRuntime(Fixtures.properties(directory));var first=Fixtures.job("KUBERNETES","MANUAL");runtime.deploy(first,context(ca),null,Fixtures.IMAGE,()->{});
  race=true;assertThrows(ExecutionFailure.LeaseLost.class,()->runtime.deploy(next(first),context(ca),null,"registry.example/web@sha256:"+"c".repeat(64),()->{}));
  assertEquals(999,resources.get(path()).path("metadata").path("annotations").path("shipora.io/fence").asLong());
 }
 @Test void foreignResourcesAreNeverAdoptedOrRemoved(){
  var runtime=new KubernetesRuntime(Fixtures.properties(directory));var job=Fixtures.job("KUBERNETES","MANUAL");runtime.deploy(job,context(ca),null,Fixtures.IMAGE,()->{});
  ((ObjectNode)resources.get(path()).path("metadata").path("labels")).put("shipora.io/organization",UUID.randomUUID().toString());int before=writes.get();
  assertThrows(ExecutionFailure.class,()->runtime.deploy(next(job),context(ca),null,Fixtures.IMAGE,()->{}));assertEquals(before,writes.get());
 }
 @Test void untrustedCertificatePreventsCredentialDeliveryAndMutation(){
  var runtime=new KubernetesRuntime(Fixtures.properties(directory));assertThrows(ExecutionFailure.class,()->runtime.deploy(Fixtures.job("KUBERNETES","MANUAL"),context(""),null,Fixtures.IMAGE,()->{}));
  assertEquals(0,requests.get());assertEquals(0,writes.get());
 }
}
