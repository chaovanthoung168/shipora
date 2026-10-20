package com.shipora.git.adapter.out.github;

import com.shipora.git.application.port.out.GitProvider;
import com.shipora.git.application.port.out.GitHubRegistration;
import com.shipora.git.application.dto.GitHubAppCredentials;
import java.util.function.Supplier;
import com.shipora.git.configuration.GitHubProperties;
import com.shipora.git.domain.GitException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.shipora.git.domain.GitException.Kind.*;

public final class GitHubClient implements GitProvider, GitHubRegistration, AutoCloseable {
    private static final int MAX_BODY = 4 * 1024 * 1024;
    private static final int MAX_PAGES = 100;
    private final Supplier<GitHubProperties> configuration;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http;
    private final Semaphore slots = new Semaphore(4);

    public GitHubClient(GitHubProperties properties, Clock clock) {
        this(properties, clock, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    GitHubClient(GitHubProperties properties, Clock clock, HttpClient http) {
        this(() -> properties, clock, http);
    }

    public GitHubClient(Supplier<GitHubProperties> configuration, Clock clock) {
        this(configuration, clock, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    GitHubClient(Supplier<GitHubProperties> configuration, Clock clock, HttpClient http) {
        this.configuration = configuration; this.clock = clock; this.http = http;
    }
    private GitHubProperties properties() { return configuration.get(); }

    @Override public GitHubAppCredentials convert(String code, URI callbackUrl) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{1,512}")) throw new GitException(INVALID, "Invalid GitHub setup code.");
        return bounded(() -> {
            JsonNode result = request(api("/app-manifests/" + code + "/conversions"), null, "{}", false);
            String id = text(result, "id"), slug = text(result, "slug"), client = text(result, "client_id");
            String secret = text(result, "client_secret"), webhook = text(result, "webhook_secret"), pem = text(result, "pem");
            if (!id.matches("[1-9][0-9]{0,19}") || !slug.matches("[A-Za-z0-9-]{1,100}")
                    || blank(client) || client.length() > 255 || blank(secret) || secret.length() > 4096
                    || blank(webhook) || webhook.length() > 4096) throw providerFailure();
            try { RsaPrivateKeys.parse(pem); } catch (Exception e) { throw providerFailure(); }
            return new GitHubAppCredentials(id, slug, client, secret, pem, webhook, callbackUrl);
        });
    }

    @Override public void requireConfigured() {
        if (blank(properties().appId()) || !properties().appId().matches("[1-9][0-9]*") || blank(properties().clientId())
                || blank(properties().clientSecret()) || (properties().privateKey() == null && blank(properties().privateKeyPem())) || properties().callbackUrl() == null) {
            throw new GitException(NOT_CONFIGURED, "Complete GitHub setup first using /api/git/setup/github.");
        }
        URI callback = properties().callbackUrl();
        boolean https = "https".equals(callback.getScheme());
        boolean local = "http".equals(callback.getScheme()) && Set.of("localhost", "127.0.0.1").contains(callback.getHost());
        if ((!https && !local) || callback.getHost() == null || callback.getQuery() != null || callback.getFragment() != null
                || callback.getUserInfo() != null) throw new GitException(NOT_CONFIGURED, "Invalid configured GitHub callback URL.");
    }
    @Override public String authorizationUrl(String state, String challenge) {
        return "https://github.com/login/oauth/authorize?client_id=" + encode(properties().clientId())
                + "&redirect_uri=" + encode(properties().callbackUrl().toString()) + "&state=" + encode(state)
                + "&code_challenge=" + encode(challenge) + "&code_challenge_method=S256";
    }
    @Override public VerifiedInstallation verifyInstallation(String id, String code, String verifier) {
        requireConfigured();
        return bounded(() -> {
            String form = "client_id=" + encode(properties().clientId()) + "&client_secret=" + encode(properties().clientSecret())
                    + "&code=" + encode(code) + "&code_verifier=" + encode(verifier)
                    + "&redirect_uri=" + encode(properties().callbackUrl().toString());
            JsonNode token = request("https://github.com/login/oauth/access_token", null, form, true);
            String userToken = text(token, "access_token");
            if (blank(userToken)) throw new GitException(FORBIDDEN, "GitHub authorization failed. Start a new connection.");
            // The user must have access to this installation AND administer its owning account.
            boolean accessible = false;
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonNode items = request(api("/user/installations?per_page=100&page=" + page), userToken, null, false).path("installations");
                requireArray(items);
                for (JsonNode item : items) if (id.equals(text(item, "id"))) accessible = true;
                if (accessible || items.size() < 100) break;
                if (page == MAX_PAGES) throw providerFailure();
            }
            if (!accessible) throw new GitException(FORBIDDEN, "You cannot connect this GitHub installation.");
            JsonNode installation = request(api("/app/installations/" + id), appJwt(), null, false);
            if (!properties().appId().equals(text(installation, "app_id")) || !installation.path("suspended_at").isNull()) {
                throw new GitException(FORBIDDEN, "This GitHub installation is unavailable.");
            }
            JsonNode account = installation.path("account");
            String login = text(account, "login");
            if (blank(login) || !login.matches("[A-Za-z0-9-]{1,100}")) throw providerFailure();
            if ("Organization".equals(text(account, "type"))) {
                JsonNode membership = request(api("/user/memberships/orgs/" + login), userToken, null, false);
                if (!"admin".equals(text(membership, "role")) || !"active".equals(text(membership, "state"))) {
                    throw new GitException(FORBIDDEN, "A GitHub organization owner must connect this installation.");
                }
            } else if ("User".equals(text(account, "type"))) {
                JsonNode user = request(api("/user"), userToken, null, false);
                if (!text(account, "id").equals(text(user, "id"))) {
                    throw new GitException(FORBIDDEN, "The GitHub account owner must connect this installation.");
                }
            } else throw new GitException(FORBIDDEN, "Unsupported GitHub account type.");
            return new VerifiedInstallation(id, login);
        });
    }
    @Override public InstallationDiscovery discoverInstallations(String code, String verifier) {
        requireConfigured();
        return bounded(() -> {
            String form = "client_id=" + encode(properties().clientId()) + "&client_secret=" + encode(properties().clientSecret())
                    + "&code=" + encode(code) + "&code_verifier=" + encode(verifier)
                    + "&redirect_uri=" + encode(properties().callbackUrl().toString());
            String userToken = text(request("https://github.com/login/oauth/access_token", null, form, true), "access_token");
            if (blank(userToken)) throw new GitException(FORBIDDEN, "GitHub authorization failed. Connect again.");
            String appToken = appJwt();
            JsonNode app = request(api("/app"), appToken, null, false);
            String slug = text(app, "slug");
            if (!slug.matches("[A-Za-z0-9-]{1,100}")) throw providerFailure();
            String installationUrl = "https://github.com/apps/" + slug + "/installations/new";
            JsonNode user = request(api("/user"), userToken, null, false);
            Map<String, VerifiedInstallation> owned = new LinkedHashMap<>();
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonNode items = request(api("/user/installations?per_page=100&page=" + page), userToken, null, false).path("installations");
                requireArray(items);
                for (JsonNode item : items) {
                    String id = text(item, "id");
                    if (!id.matches("[1-9][0-9]{0,19}")) throw providerFailure();
                    if (!properties().appId().equals(text(item, "app_id")) || !item.path("suspended_at").isNull()) continue;
                    JsonNode installation = request(api("/app/installations/" + id), appToken, null, false);
                    if (!properties().appId().equals(text(installation, "app_id")) || !installation.path("suspended_at").isNull()) continue;
                    JsonNode account = installation.path("account");
                    String login = text(account, "login");
                    if (!login.matches("[A-Za-z0-9-]{1,100}")) throw providerFailure();
                    boolean owner = false;
                    if ("User".equals(text(account, "type"))) {
                        owner = !blank(text(user, "id")) && text(user, "id").equals(text(account, "id"));
                    } else if ("Organization".equals(text(account, "type"))) {
                        JsonNode membership = request(api("/user/memberships/orgs/" + login), userToken, null, false);
                        owner = "admin".equals(text(membership, "role")) && "active".equals(text(membership, "state"));
                    }
                    if (owner) owned.put(id, new VerifiedInstallation(id, login));
                }
                if (items.size() < 100) return new InstallationDiscovery(List.copyOf(owned.values()), installationUrl);
            }
            throw providerFailure();
        });
    }
    @Override public void requireActiveInstallation(String externalId) {
        requireConfigured();
        bounded(() -> {
            JsonNode installation = request(api("/app/installations/" + externalId), appJwt(), null, false);
            if (!properties().appId().equals(text(installation, "app_id")) || !installation.path("suspended_at").isNull()) {
                throw new GitException(FORBIDDEN, "GitHub access changed. Connect again.");
            }
            return null;
        });
    }
    @Override public List<RepositorySnapshot> repositories(String externalId) {
        requireConfigured();
        return bounded(() -> {
            String token = text(request(api("/app/installations/" + externalId + "/access_tokens"), appJwt(), "{}", false), "token");
            if (blank(token)) throw providerFailure();
            Map<String, RepositorySnapshot> result = new LinkedHashMap<>();
            for (int page = 1; page <= MAX_PAGES; page++) {
                JsonNode items = request(api("/installation/repositories?per_page=100&page=" + page), token, null, false).path("repositories");
                requireArray(items);
                for (JsonNode item : items) {
                    String id = text(item, "id"), name = text(item, "full_name"), clone = text(item, "clone_url");
                    String branch = text(item, "default_branch");
                    if (blank(id) || !id.matches("[1-9][0-9]{0,19}") || blank(name) || name.length() > 255
                            || blank(clone) || !clone.startsWith("https://github.com/") || !item.path("private").isBoolean()) throw providerFailure();
                    if (blank(branch)) branch = "main";
                    if (branch.length() > 255) throw providerFailure();
                    result.put(id, new RepositorySnapshot(id, name, clone, branch, item.path("private").asBoolean()));
                }
                if (items.size() < 100) return List.copyOf(result.values());
            }
            throw new GitException(PROVIDER, "Repository synchronization exceeds the configured 10,000 repository limit.");
        });
    }
    @Override public ReadAccess readAccess(String installation,String repository,String fullName,String branch){
        requireConfigured();
        if(!installation.matches("[1-9][0-9]{0,19}") || !repository.matches("[1-9][0-9]{0,19}") || !fullName.matches("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+"))throw providerFailure();
        return bounded(()->{
            String body=json.writeValueAsString(Map.of("repository_ids",List.of(new java.math.BigInteger(repository)),"permissions",Map.of("contents","read")));
            var result=request(api("/app/installations/"+installation+"/access_tokens"),appJwt(),body,false);
            String token=text(result,"token");java.time.Instant expires=java.time.Instant.parse(text(result,"expires_at"));
            if(blank(token) || !expires.isAfter(clock.instant().plusSeconds(30)))throw providerFailure();
            String head=null;
            if(branch!=null){
                if(!branch.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,254}") || branch.contains("..") || branch.contains("//"))throw providerFailure();
                String ref=java.util.Arrays.stream(branch.split("/")).map(GitHubClient::encode).collect(java.util.stream.Collectors.joining("/"));
                head=request(api("/repos/"+fullName+"/git/ref/heads/"+ref),token,null,false).path("object").path("sha").asText("");
                if(!head.matches("[0-9a-f]{40}|[0-9a-f]{64}"))throw providerFailure();
            }
            return new ReadAccess(token,expires,head);
        });
    }
    @Override public List<com.shipora.git.application.dto.CommitResult> commits(String installation, String repository,
            String fullName, String branch, int page, int size) {
        if (branch == null || !branch.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,254}")
                || branch.contains("..") || branch.contains("//") || branch.endsWith("/")
                || page < 0 || page > 1000 || size < 1 || size > 100)
            throw new GitException(INVALID, "Invalid branch or commit pagination.");
        // Reuse single-repository, contents-read credentials; never return them to callers.
        var access = readAccess(installation, repository, fullName, null);
        return bounded(() -> {
            JsonNode items = request(api("/repos/" + fullName + "/commits?sha=" + encode(branch)
                    + "&per_page=" + size + "&page=" + (page + 1)), access.token(), null, false);
            requireArray(items);
            if (items.size() > size) throw providerFailure();
            var result = new ArrayList<com.shipora.git.application.dto.CommitResult>();
            for (JsonNode item : items) {
                String sha = text(item, "sha");
                if (!sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw providerFailure();
                var commit = item.path("commit");
                String message = text(commit, "message").lines().findFirst().orElse("");
                if (message.length() > 500) message = message.substring(0, 500);
                String author = text(commit.path("author"), "name");
                if (author.length() > 150) author = author.substring(0, 150);
                String date = text(commit.path("committer"), "date");
                result.add(new com.shipora.git.application.dto.CommitResult(sha, message, author,
                        blank(date) ? null : Instant.parse(date)));
            }
            return List.copyOf(result);
        });
    }
    private String appJwt() {
        try {
            String pem = blank(properties().privateKeyPem()) ? Files.readString(properties().privateKey()) : properties().privateKeyPem();
            PrivateKey key = RsaPrivateKeys.parse(pem);
            long now = clock.instant().getEpochSecond();
            String unsigned = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "."
                    + base64("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\"" + properties().appId() + "\"}");
            Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key);
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GitException e) { throw e; }
        catch (Exception e) { throw new GitException(NOT_CONFIGURED, "Cannot load the configured GitHub App private key."); }
    }
    private JsonNode request(String url, String token, String body, boolean form) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").header("User-Agent", "Shipora-Git-Service")
                    .header("X-GitHub-Api-Version", properties().apiVersion());
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (body == null) builder.GET();
            else builder.header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            // Cancel oversized responses before retaining their complete contents.
            pending = http.sendAsync(builder.build(), ignored -> new LimitedBodySubscriber(MAX_BODY));
            // Also bound body completion, including a peer that sends headers and then stalls.
            var response = pending.get(20, TimeUnit.SECONDS);
            if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404) {
                throw new GitException(FORBIDDEN, "GitHub access was denied. Check installation access and App permissions.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw providerFailure();
            return json.readTree(response.body());
        } catch (GitException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw providerFailure(); }
        catch (Exception e) { throw providerFailure(); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }
    private <T> T bounded(Callable<T> operation) {
        if (!slots.tryAcquire()) throw new GitException(CONFLICT, "GitHub requests are busy. Retry shortly.");
        try { return operation.call(); }
        catch (GitException e) { throw e; }
        catch (Exception e) { throw providerFailure(); }
        finally { slots.release(); }
    }
    private static void requireArray(JsonNode node) { if (!node.isArray()) throw providerFailure(); }
    private static GitException providerFailure() { return new GitException(PROVIDER, "GitHub request failed. Retry shortly."); }
    private static String api(String path) { return "https://api.github.com" + path; }
    private static String text(JsonNode node, String field) { return node.path(field).asText(""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String base64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    @Override public void close() { http.close(); }
}
