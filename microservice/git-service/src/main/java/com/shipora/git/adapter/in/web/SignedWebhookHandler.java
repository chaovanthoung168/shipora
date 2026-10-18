package com.shipora.git.adapter.in.web;

import com.shipora.git.application.port.in.WebhookUseCases;
import com.shipora.git.application.port.out.GitStore.WebhookDelivery;
import com.shipora.git.domain.GitException;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.shipora.git.domain.GitException.Kind.*;

public final class SignedWebhookHandler {
    public static final int MAX_BODY = 2 * 1024 * 1024;
    private final Supplier<String> secretSource;
    private final WebhookUseCases useCases;
    private final JsonMapper json = JsonMapper.builder().build();

    public SignedWebhookHandler(String secret, WebhookUseCases useCases) { this(() -> secret, useCases); }
    public SignedWebhookHandler(Supplier<String> secretSource, WebhookUseCases useCases) {
        this.secretSource = secretSource; this.useCases = useCases;
    }

    public boolean handle(String signature, String deliveryId, String event, byte[] body) {
        String secret = secretSource.get();
        if (secret == null || secret.isBlank()) throw new GitException(NOT_CONFIGURED, "GitHub webhooks are not configured.");
        if (body.length > MAX_BODY) throw new GitException(TOO_LARGE, "Webhook body exceeds 2 MiB.");
        if (signature == null || !signature.matches("sha256=[0-9a-f]{64}") || !validSignature(secret, signature, body)) {
            throw new GitException(FORBIDDEN, "Invalid webhook signature.");
        }
        WebhookDelivery delivery;
        try {
            if (deliveryId == null || !UUID.fromString(deliveryId).toString().equals(deliveryId)
                    || event == null || !event.matches("[a-z_]{1,100}")) throw new IllegalArgumentException();
            JsonNode payload = json.readTree(body);
            if (payload == null || !payload.isObject()) throw new IllegalArgumentException();
            String installation = payload.path("installation").path("id").asText("");
            String repository = payload.path("repository").path("id").asText("");
            String action = payload.path("action").asText("");
            if (!installation.isEmpty() && !installation.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException();
            if (!repository.isEmpty() && !repository.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException();
            String commit = "push".equals(event) ? payload.path("after").asText("") : "";
            if (!commit.isEmpty() && !commit.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IllegalArgumentException();
            // Fail closed on permission changes. Reconnect or sync explicitly after reviewing access.
            boolean disableInstallation = "installation".equals(event) && Set.of("deleted", "suspend", "new_permissions_accepted").contains(action);
            boolean disableRepositories = "installation_repositories".equals(event)
                    || ("repository".equals(event) && Set.of("deleted", "transferred", "privatized", "publicized", "renamed").contains(action));
            delivery = new WebhookDelivery(deliveryId, event, emptyNull(installation), emptyNull(repository),
                    emptyNull(commit), disableInstallation, disableRepositories);
        } catch (GitException e) { throw e; }
        catch (Exception e) { throw new GitException(INVALID, "Invalid GitHub webhook payload or headers."); }
        return useCases.receive(delivery);
    }
    private boolean validSignature(String secret, String signature, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature.substring(7)));
        } catch (Exception e) { throw new IllegalStateException("Cannot validate webhook signature", e); }
    }
    private static String emptyNull(String value) { return value.isEmpty() ? null : value; }
}
