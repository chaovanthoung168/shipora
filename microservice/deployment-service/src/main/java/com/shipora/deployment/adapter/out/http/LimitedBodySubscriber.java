package com.shipora.deployment.adapter.out.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream output=new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private Flow.Subscription subscription;
    LimitedBodySubscriber(int limit){this.limit=limit;}
    @Override public CompletionStage<byte[]> getBody(){return result;}
    @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
    @Override public void onNext(List<ByteBuffer> buffers){
        for(var buffer:buffers){
            if(buffer.remaining()>limit-output.size()){subscription.cancel();result.completeExceptionally(new IllegalStateException("Response too large"));return;}
            byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);output.writeBytes(bytes);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable e){result.completeExceptionally(e);}
    @Override public void onComplete(){result.complete(output.toByteArray());}
}
