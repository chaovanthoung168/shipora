package com.shipora.application.adapter.out.http;

import com.shipora.application.configuration.CatalogProperties;
import com.shipora.application.domain.ApplicationException;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpResourceCatalogTest {
    final UUID org=UUID.randomUUID(),id=UUID.randomUUID();
    HttpClient http;HttpResourceCatalog catalog;HttpResponse<byte[]> response;
    final List<HttpRequest> requests=new ArrayList<>();
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        http=mock(HttpClient.class);response=mock(HttpResponse.class);
        catalog=new HttpResourceCatalog(new CatalogProperties(URI.create("http://project.example"),URI.create("http://git.example")),ignored->"Basic test-caller",http);
        when(http.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(invocation->{requests.add(invocation.getArgument(0));return CompletableFuture.completedFuture(response);});
        reply(200,"{\"id\":\""+id+"\",\"organizationId\":\""+org+"\"}");
    }
    void reply(int status,String body){when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));}
    @Test void forwardsTheCallerToAFixedOriginAndChecksOwnership(){
        catalog.requireProject(org,id);
        assertEquals("Basic test-caller",requests.getFirst().headers().firstValue("Authorization").orElseThrow());
        assertEquals("http://project.example/api/projects/"+id,requests.getFirst().uri().toString());
        assertTrue(requests.getFirst().headers().firstValue("X-Organization-Id").isEmpty());
        reply(200,"{\"id\":\""+id+"\",\"organizationId\":\""+UUID.randomUUID()+"\"}");
        assertEquals(ApplicationException.Kind.NOT_FOUND,assertThrows(ApplicationException.class,()->catalog.requireProject(org,id)).getKind());
    }
    @Test void unavailableRepositoryAndInactiveServerAreRejected(){
        reply(200,"{\"id\":\""+id+"\",\"available\":false,\"defaultBranch\":\"main\"}");
        assertEquals(ApplicationException.Kind.CONFLICT,assertThrows(ApplicationException.class,()->catalog.requireRepository(org,id)).getKind());
        reply(200,"{\"id\":\""+id+"\",\"organizationId\":\""+org+"\",\"status\":\"OFFLINE\"}");
        assertThrows(ApplicationException.class,()->catalog.requireActiveServer(org,id));
    }
    @Test void availableRepositoryReturnsItsBranchAndActiveServerPasses(){
        reply(200,"{\"id\":\""+id+"\",\"available\":true,\"defaultBranch\":\"develop\"}");
        assertEquals("develop",catalog.requireRepository(org,id));
        reply(200,"{\"id\":\""+id+"\",\"organizationId\":\""+org+"\",\"status\":\"ACTIVE\"}");
        assertDoesNotThrow(()->catalog.requireActiveServer(org,id));
    }
    @Test void upstreamAuthorizationErrorsAreNotConvertedToNotFoundOrSuccess(){
        for(int status:new int[]{401,403}){
            reply(status,"private provider details");assertEquals(ApplicationException.Kind.FORBIDDEN,assertThrows(ApplicationException.class,()->catalog.requireProject(org,id)).getKind());
        }
        reply(404,"private");assertEquals(ApplicationException.Kind.NOT_FOUND,assertThrows(ApplicationException.class,()->catalog.requireProject(org,id)).getKind());
    }
    @Test void malformedAndRedirectResponsesProduceSanitizedDependencyErrors(){
        for(String body:List.of("[]","invalid","{\"id\":\""+id+"\",\"available\":\"true\"}")){
            reply(200,body);var error=assertThrows(ApplicationException.class,()->catalog.requireRepository(org,id));assertEquals(ApplicationException.Kind.DEPENDENCY,error.getKind());
        }
        reply(302,"secret-token");assertFalse(assertThrows(ApplicationException.class,()->catalog.requireProject(org,id)).getMessage().contains("secret-token"));
    }
    @Test @SuppressWarnings("unchecked") void interruptedRequestsAreCancelledAndPreserveInterrupt(){
        var pending=new CompletableFuture<HttpResponse<byte[]>>();
        doAnswer(invocation->{Thread.currentThread().interrupt();return pending;}).when(http).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        try{assertThrows(ApplicationException.class,()->catalog.requireProject(org,id));assertTrue(Thread.currentThread().isInterrupted());assertTrue(pending.isCancelled());}
        finally{Thread.interrupted();}
    }
    @Test void oversizedBodiesAreCancelledBeforeTheyAreRetained(){
        var subscriber=new LimitedBodySubscriber(4);var subscription=mock(Flow.Subscription.class);subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));verify(subscription).cancel();assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void rejectsUntrustedUrlShapesAtStartup(){
        for(String value:List.of("http://user:password@example.com","http://example.com/path","file:///etc/passwd","https://example.com?q=x"))
            assertThrows(IllegalArgumentException.class,()->new HttpResourceCatalog(new CatalogProperties(URI.create(value),URI.create("http://git.example")),ignored->"Basic x",http));
    }
}
