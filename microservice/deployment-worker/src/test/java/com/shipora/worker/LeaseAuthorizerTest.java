package com.shipora.worker;

import com.shipora.pipeline.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LeaseAuthorizerTest {
    @Test void forwardsWorkerAuthenticationAndRejectsCrossTenantAndReassignedLeases() throws Exception {
        var job = Fixtures.job("DOCKER", "MANUAL");
        var response = new AtomicReference<Object>(job);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var http = new InternalHttp()) {
            server.createContext("/", exchange -> {
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("Basic worker-test", exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("true", exchange.getRequestHeaders().getFirst("X-Shipora-Request"));
                assertTrue(new String(exchange.getRequestBody().readAllBytes()).contains(job.leaseToken().toString()));
                byte[] bytes = http.json.writeValueAsBytes(response.get());
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            var gate = new LeaseAuthorizer(http, "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals(job, gate.authorize(Fixtures.ORG, job.jobId(), job.leaseToken(), "Basic worker-test"));
            assertEquals(403, assertThrows(InternalHttp.RemoteFailure.class, () -> gate.authorize(UUID.randomUUID(), job.jobId(), job.leaseToken(), "Basic worker-test")).status);
            response.set(new Protocol.Job(job.jobId(), UUID.randomUUID(), job.leaseExpiresAt(), job.attempt(), job.deployment(), job.fence()));
            assertEquals(403, assertThrows(InternalHttp.RemoteFailure.class, () -> gate.authorize(Fixtures.ORG, job.jobId(), job.leaseToken(), "Basic worker-test")).status);
        } finally { server.stop(0); }
    }
}
