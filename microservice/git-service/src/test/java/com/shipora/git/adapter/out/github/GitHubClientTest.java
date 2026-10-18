package com.shipora.git.adapter.out.github;

import com.shipora.git.configuration.GitHubProperties;
import com.shipora.git.domain.GitException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubClientTest {
    @TempDir Path directory;
    private HttpClient http;
    private GitHubClient client;
    private KeyPair key;
    private final Map<String, String> responses = new HashMap<>();
    private final List<HttpRequest> requests = new ArrayList<>();

    @BeforeEach @SuppressWarnings("unchecked") void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair();
        Path privateKey = directory.resolve("app.pem");
        Files.writeString(privateKey, "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----");
        http = mock(HttpClient.class);
        client = new GitHubClient(new GitHubProperties("42", "client", "secret", privateKey,
                URI.create("http://localhost:8084/api/git/github/callback"), "webhook", "2026-03-10"),
                Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC), http);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0); requests.add(request);
            String path = request.uri().getPath() + (request.uri().getQuery() == null ? "" : "?" + request.uri().getQuery());
            String body = responses.get(path);
            assertNotNull(body, "Unexpected GitHub request: " + path);
            HttpResponse<byte[]> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(response);
        });
        responses.put("/login/oauth/access_token", "{\"access_token\":\"user-token\"}");
        responses.put("/user/installations?per_page=100&page=1", "{\"installations\":[{\"id\":123}]}");
        responses.put("/app/installations/123", "{\"app_id\":42,\"suspended_at\":null,\"account\":{\"id\":1,\"login\":\"owner\",\"type\":\"User\"}}");
        responses.put("/user", "{\"id\":1}");
    }
    @Test void verifiesPersonalAccountOwnerAndSignsShortLivedAppJwt() throws Exception {
        assertEquals("owner", client.verifyInstallation("123", "code", "verifier").accountName());
        var request = requests.stream().filter(r -> r.uri().getPath().equals("/app/installations/123")).findFirst().orElseThrow();
        String[] parts = request.headers().firstValue("Authorization").orElseThrow().substring(7).split("\\.");
        Signature signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(key.getPublic());
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
        assertTrue(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8).contains("\"iss\":\"42\""));
    }
    @Test void accessibleInstallationDoesNotAllowDifferentPersonalAccountToClaimIt() {
        responses.put("/user", "{\"id\":2}");
        assertEquals(GitException.Kind.FORBIDDEN, assertThrows(GitException.class,
                () -> client.verifyInstallation("123", "code", "verifier")).getKind());
    }
    @Test void organizationMemberCannotClaimWholeInstallation() {
        organization(); responses.put("/user/memberships/orgs/owner", "{\"role\":\"member\",\"state\":\"active\"}");
        assertThrows(GitException.class, () -> client.verifyInstallation("123", "code", "verifier"));
    }
    @Test void organizationOwnerCanConnect() {
        organization(); responses.put("/user/memberships/orgs/owner", "{\"role\":\"admin\",\"state\":\"active\"}");
        assertEquals("123", client.verifyInstallation("123", "code", "verifier").externalId());
    }
    @Test void userWithoutInstallationAccessIsRejectedBeforeAppTokenRequest() {
        responses.put("/user/installations?per_page=100&page=1", "{\"installations\":[]}");
        assertThrows(GitException.class, () -> client.verifyInstallation("123", "code", "verifier"));
        assertEquals(2, requests.size());
    }
    @Test void syncUsesInstallationTokenAndReadsRepositoryMetadata() {
        responses.put("/app/installations/123/access_tokens", "{\"token\":\"installation-token\"}");
        responses.put("/installation/repositories?per_page=100&page=1", "{\"repositories\":[{\"id\":5,\"full_name\":\"owner/repo\",\"clone_url\":\"https://github.com/owner/repo.git\",\"default_branch\":\"develop\",\"private\":true}]}");
        var result = client.repositories("123"); assertEquals(1, result.size()); assertEquals("develop", result.getFirst().defaultBranch());
        assertTrue(result.getFirst().privateRepository());
        assertEquals("Bearer installation-token", requests.getLast().headers().firstValue("Authorization").orElseThrow());
    }
    @Test void malformedGithubSnapshotCannotBePersistedAsEmptySuccess() {
        responses.put("/app/installations/123/access_tokens", "{\"token\":\"installation-token\"}");
        responses.put("/installation/repositories?per_page=100&page=1", "{\"message\":\"unexpected\"}");
        assertThrows(GitException.class, () -> client.repositories("123"));
    }
    @Test void missingConfigurationFailsWithSanitizedMessage() {
        var empty = new GitHubClient(new GitHubProperties(null, null, "secret", null, null, null, "2026-03-10"), Clock.systemUTC(), http);
        var error = assertThrows(GitException.class, empty::requireConfigured);
        assertEquals(GitException.Kind.NOT_CONFIGURED, error.getKind()); assertFalse(error.getMessage().contains("secret"));
    }
    @Test void manifestConversionWorksBeforeAnyAppIsConfiguredAndDoesNotSendCredentials() {
        var data = new java.util.LinkedHashMap<String,Object>();
        data.put("id",42); data.put("slug","manifest-app"); data.put("client_id","new-client");
        data.put("client_secret","new-secret"); data.put("webhook_secret","new-webhook");
        data.put("pem","-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(key.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        responses.put("/app-manifests/setup-code/conversions",tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(data));
        var empty = new GitHubClient(new GitHubProperties(null,null,null,null,null,null,"2026-03-10"),Clock.systemUTC(),http);
        var result = empty.convert("setup-code",URI.create("https://instance.example/callback"));
        assertEquals("42",result.appId()); assertEquals("new-client",result.clientId());
        assertTrue(requests.getLast().headers().firstValue("Authorization").isEmpty());
        assertEquals("POST",requests.getLast().method()); assertFalse(result.toString().contains("new-secret"));
    }
    @Test void malformedManifestResponseNeverProducesStoredCredentials() {
        responses.put("/app-manifests/code/conversions","{\"id\":42,\"slug\":\"../../bad\"}");
        assertEquals(GitException.Kind.PROVIDER,assertThrows(GitException.class,()->client.convert("code",URI.create("https://instance.example/callback"))).getKind());
        assertThrows(GitException.class,()->client.convert("../invalid",URI.create("https://instance.example/callback")));
    }
    @Test void dynamicallySuppliedCredentialsBecomeUsableWithoutRestart() {
        var reference = new java.util.concurrent.atomic.AtomicReference<>(new GitHubProperties(null,null,null,null,null,null,"2026-03-10"));
        var dynamic = new GitHubClient(reference::get,Clock.systemUTC(),http);
        assertThrows(GitException.class,dynamic::requireConfigured);
        reference.set(new GitHubProperties("42","new-client","secret",null,URI.create("https://instance.example/callback"),"webhook","2026-03-10","pem"));
        assertDoesNotThrow(dynamic::requireConfigured);
        assertTrue(dynamic.authorizationUrl("state","challenge").contains("client_id=new-client"));
    }
    @Test void githubPkcs1PrivateKeysAreAcceptedWithoutExternalConversion() throws Exception {
        var rsa=(java.security.interfaces.RSAPrivateCrtKey)key.getPrivate();
        var data=new java.io.ByteArrayOutputStream();
        for(var value:List.of(java.math.BigInteger.ZERO,rsa.getModulus(),rsa.getPublicExponent(),rsa.getPrivateExponent(),
                rsa.getPrimeP(),rsa.getPrimeQ(),rsa.getPrimeExponentP(),rsa.getPrimeExponentQ(),rsa.getCrtCoefficient()))
            data.writeBytes(der(2,value.toByteArray()));
        String pem="-----BEGIN RSA PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(der(0x30,data.toByteArray()))+"\n-----END RSA PRIVATE KEY-----";
        assertArrayEquals(key.getPrivate().getEncoded(),RsaPrivateKeys.parse(pem).getEncoded());
    }
    byte[] der(int tag,byte[] data){
        var output=new java.io.ByteArrayOutputStream();output.write(tag);
        if(data.length<128)output.write(data.length);
        else if(data.length<256){output.write(0x81);output.write(data.length);}
        else{output.write(0x82);output.write(data.length>>>8);output.write(data.length&255);}
        output.writeBytes(data);return output.toByteArray();
    }
    @Test void bodySubscriberCancelsOversizedResponse() {
        var subscriber = new LimitedBodySubscriber(4); var subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription); subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        verify(subscription).cancel(); assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test @SuppressWarnings("unchecked") void interruptedNetworkWaitCancelsRequestAndPreservesInterrupt() {
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        doAnswer(invocation -> { Thread.currentThread().interrupt(); return pending; })
                .when(http).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try {
            assertThrows(GitException.class, () -> client.repositories("123"));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(pending.isCancelled());
        } finally { Thread.interrupted(); }
    }
    private void organization() {
        responses.put("/app/installations/123", "{\"app_id\":42,\"suspended_at\":null,\"account\":{\"id\":1,\"login\":\"owner\",\"type\":\"Organization\"}}");
    }
    @Test void browserDiscoveryFindsOwnedInstallationWithoutManualId() {
        discoveryFixtures();
        var result = client.discoverInstallations("code", "verifier");
        assertEquals("123", result.accounts().getFirst().externalId());
        assertEquals("https://github.com/apps/shipora/installations/new", result.installationUrl());
    }
    @Test void browserDiscoveryExcludesPersonalInstallationOwnedBySomeoneElse() {
        discoveryFixtures(); responses.put("/user", "{\"id\":2}");
        assertTrue(client.discoverInstallations("code", "verifier").accounts().isEmpty());
    }
    @Test void browserDiscoveryExcludesOrganizationMemberWhoIsNotOwner() {
        discoveryFixtures(); organization(); responses.put("/user/memberships/orgs/owner", "{\"role\":\"member\",\"state\":\"active\"}");
        assertTrue(client.discoverInstallations("code", "verifier").accounts().isEmpty());
    }
    @Test void browserDiscoveryProvidesInstallLinkWhenAppNotInstalled() {
        discoveryFixtures(); responses.put("/user/installations?per_page=100&page=1", "{\"installations\":[]}");
        var result = client.discoverInstallations("code", "verifier");
        assertTrue(result.accounts().isEmpty()); assertTrue(result.installationUrl().startsWith("https://github.com/apps/"));
    }
    @Test void activeCheckRejectsSuspensionBeforeBrowserSelectionLinksInstallation() {
        responses.put("/app/installations/123", "{\"app_id\":42,\"suspended_at\":\"2026-10-18T00:00:00Z\"}");
        assertThrows(GitException.class, () -> client.requireActiveInstallation("123"));
    }
    private void discoveryFixtures() {
        responses.put("/app", "{\"slug\":\"shipora\"}");
        responses.put("/user/installations?per_page=100&page=1", "{\"installations\":[{\"id\":123,\"app_id\":42,\"suspended_at\":null}]}");
    }
}
