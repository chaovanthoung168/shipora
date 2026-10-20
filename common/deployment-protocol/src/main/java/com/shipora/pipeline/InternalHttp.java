package com.shipora.pipeline;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
public final class InternalHttp implements AutoCloseable {
 public final JsonMapper json=JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
 private final HttpClient http;
 public InternalHttp(){http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();}
 public InternalHttp(HttpClient client){http=client;}
 public static URI origin(String value){
  URI uri=URI.create(value);
  if(uri.getHost()==null || !Set.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
   || !(uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalArgumentException("Internal URL must be a trusted HTTP(S) origin.");
  return URI.create(value.replaceAll("/+$",""));
 }
 public JsonNode send(URI origin,String path,String method,String authorization,Object body){
  var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(12)).header("Accept","application/json").header("X-Shipora-Request","true");
  if(authorization!=null)builder.header("Authorization",authorization);
  if(body!=null)builder.header("Content-Type","application/json");
  return send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build());
 }
 public JsonNode send(HttpRequest request){
  var pending=http.sendAsync(request,ignored->new LimitedBody(4*1024*1024));
  try{
   var response=pending.get(15,TimeUnit.SECONDS);
   if(response.statusCode()<200 || response.statusCode()>299)throw new RemoteFailure(response.statusCode());
   return response.body().length==0?json.nullNode():json.readTree(response.body());
  }catch(RemoteFailure e){throw e;}
  catch(InterruptedException e){Thread.currentThread().interrupt();throw new RemoteFailure(503);}
  catch(Exception e){throw new RemoteFailure(503);}
  finally{if(!pending.isDone())pending.cancel(true);}
 }
 @Override public void close(){http.shutdownNow();}
 public static final class RemoteFailure extends RuntimeException {
  public final int status;public RemoteFailure(int status){super("Internal service request failed ("+status+").");this.status=status;}
 }
 private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]>{
  final int limit;final ByteArrayOutputStream output=new ByteArrayOutputStream();final CompletableFuture<byte[]> result=new CompletableFuture<>();Flow.Subscription subscription;
  LimitedBody(int limit){this.limit=limit;}
  public CompletionStage<byte[]> getBody(){return result;}
  public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
  public void onNext(List<ByteBuffer> buffers){for(var b:buffers){if(b.remaining()>limit-output.size()){subscription.cancel();result.completeExceptionally(new IOException("Response limit"));return;}byte[] data=new byte[b.remaining()];b.get(data);output.writeBytes(data);}subscription.request(1);}
  public void onError(Throwable e){result.completeExceptionally(e);}
  public void onComplete(){result.complete(output.toByteArray());}
 }
}
