package com.shipora.git.adapter.in.web;

import com.shipora.git.application.port.in.WebhookUseCases;
import com.shipora.git.application.port.out.GitStore.WebhookDelivery;
import com.shipora.git.domain.GitException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignedWebhookHandlerTest {
    private static final String SECRET = "test-webhook-secret";
    private final WebhookUseCases useCases = mock(WebhookUseCases.class);
    private final SignedWebhookHandler handler = new SignedWebhookHandler(SECRET, useCases);
    private final String delivery = UUID.randomUUID().toString();
    private final byte[] body = "{\"installation\":{\"id\":123},\"repository\":{\"id\":456},\"after\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}".getBytes(StandardCharsets.UTF_8);

    @Test void validSignatureDispatchesPushMetadataWithoutStoringPayload() throws Exception {
        when(useCases.receive(any())).thenReturn(true);
        assertTrue(handler.handle(sign(body), delivery, "push", body));
        var captor = ArgumentCaptor.forClass(WebhookDelivery.class); verify(useCases).receive(captor.capture());
        assertEquals("123", captor.getValue().installationExternalId());
        assertEquals("456", captor.getValue().repositoryExternalId());
        assertEquals("a".repeat(40), captor.getValue().commitSha());
    }
    @Test void tamperedBodyIsRejectedBeforeDispatch() throws Exception {
        assertThrows(GitException.class, () -> handler.handle(sign(body), delivery, "push", "{}".getBytes()));
        verifyNoInteractions(useCases);
    }
    @Test void missingOrMalformedSignatureIsRejected() {
        for (String signature : Arrays.asList(null, "", "sha1=123", "sha256=aa")) {
            assertThrows(GitException.class, () -> handler.handle(signature, delivery, "push", body));
        }
        verifyNoInteractions(useCases);
    }
    @Test void signedMalformedJsonIsRejected() throws Exception {
        byte[] invalid = "{".getBytes();
        assertThrows(GitException.class, () -> handler.handle(sign(invalid), delivery, "push", invalid));
        verifyNoInteractions(useCases);
    }
    @Test void signedInstallationSuspensionInvalidatesAccess() throws Exception {
        byte[] suspension = "{\"installation\":{\"id\":123},\"action\":\"suspend\"}".getBytes();
        handler.handle(sign(suspension), delivery, "installation", suspension);
        verify(useCases).receive(argThat(WebhookDelivery::invalidateInstallation));
    }
    @Test void repositoryPermissionChangesInvalidateSnapshot() throws Exception {
        handler.handle(sign(body), delivery, "installation_repositories", body);
        verify(useCases).receive(argThat(WebhookDelivery::invalidateRepositories));
    }
    @Test void duplicatesReturnFalse() throws Exception {
        when(useCases.receive(any())).thenReturn(false);
        assertFalse(handler.handle(sign(body), delivery, "push", body));
    }
    @Test void oversizedAndUnconfiguredWebhooksAreRejected() {
        assertThrows(GitException.class, () -> handler.handle(null, delivery, "push", new byte[SignedWebhookHandler.MAX_BODY + 1]));
        assertThrows(GitException.class, () -> new SignedWebhookHandler("", useCases).handle(null, delivery, "push", body));
        verifyNoInteractions(useCases);
    }
    @Test void storageFailuresAreNotMisreportedAsMalformedPayloads() throws Exception {
        when(useCases.receive(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> handler.handle(sign(body), delivery, "push", body));
    }
    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
    @Test void onlyLiveBranchPushesCarryAnAutomaticDeploymentBranch()throws Exception{
        for(String ref:List.of("refs/heads/main","refs/tags/v1")){
            reset(useCases);byte[] event=("{\"installation\":{\"id\":123},\"repository\":{\"id\":456},\"ref\":\""+ref+"\",\"after\":\""+"a".repeat(40)+"\",\"deleted\":false}").getBytes(StandardCharsets.UTF_8);
            handler.handle(sign(event),delivery,"push",event);
            verify(useCases).receive(argThat(value->ref.startsWith("refs/heads/")?"main".equals(value.branch()):value.branch()==null));
        }
        reset(useCases);byte[] deleted=("{\"ref\":\"refs/heads/main\",\"after\":\""+"0".repeat(40)+"\",\"deleted\":true}").getBytes(StandardCharsets.UTF_8);
        handler.handle(sign(deleted),delivery,"push",deleted);verify(useCases).receive(argThat(value->value.branch()==null));
    }
}
