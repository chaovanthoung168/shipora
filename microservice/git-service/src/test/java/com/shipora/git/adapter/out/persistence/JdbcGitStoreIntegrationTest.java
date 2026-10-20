package com.shipora.git.adapter.out.persistence;

import com.shipora.git.application.port.out.*;
import com.shipora.git.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against an explicitly selected disposable database named shipora_git_test_*. */
@EnabledIfEnvironmentVariable(named = "GIT_TEST_JDBC_URL", matches = ".+")
class JdbcGitStoreIntegrationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private JdbcGitHubSetupStore setup;
    private JdbcTemplate jdbc;
    private JdbcGitStore store;
    private JdbcConnectionStore connections;
    private final UUID org = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-18T00:00:00Z");

    @BeforeEach void setUp() {
        String url = System.getenv("GIT_TEST_JDBC_URL");
        assertTrue(url.matches("jdbc:postgresql://[^/]+/shipora_git_test_[a-z0-9_]+"), "Use a disposable test database only.");
        var source = new DriverManagerDataSource(url, System.getenv().getOrDefault("GIT_TEST_DB_USER", "postgres"),
                System.getenv().getOrDefault("GIT_TEST_DB_PASSWORD", ""));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS git CASCADE"); jdbc.execute("CREATE SCHEMA git");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__create_git_tables.sql"),
                new ClassPathResource("db/migration/V2__git_connections_and_repository_access.sql"),
                new ClassPathResource("db/migration/V3__browser_github_connections.sql"),
                new ClassPathResource("db/migration/V4__api_connection_tokens.sql"),
                new ClassPathResource("db/migration/V5__instance_github_app_setup.sql"),new ClassPathResource("db/migration/V6__deployment_push_outbox.sql")).execute(source);
        store = new JdbcGitStore(jdbc, new DataSourceTransactionManager(source));
        connections = new JdbcConnectionStore(jdbc, new DataSourceTransactionManager(source));
        setup = new JdbcGitHubSetupStore(jdbc, new FileCredentialCipher(directory), new DataSourceTransactionManager(source));
    }
    @Test void stateIsConsumedExactlyOnceAndRejectsExpiredState() {
        store.saveState(new GitStore.ConnectionState("hash", org, "123", "verifier", now.plusSeconds(600)));
        assertTrue(store.consumeState("hash", now).isPresent()); assertTrue(store.consumeState("hash", now).isEmpty());
        store.saveState(new GitStore.ConnectionState("expired", org, "123", "verifier", now.minusSeconds(1)));
        assertTrue(store.consumeState("expired", now).isEmpty());
    }
    @Test void setupCallbackEnforcesAdministratorOrganizationExpiryAndOneTimeConsumption() {
        var callback = java.net.URI.create("https://instance.example/callback");
        setup.savePending(new GitHubSetupStore.Pending("hash",org,"admin",callback,now.plusSeconds(600)),now);
        assertTrue(setup.consumePending("hash",UUID.randomUUID(),"admin",now).isEmpty());
        assertTrue(setup.consumePending("hash",org,"other",now).isEmpty());
        assertTrue(setup.consumePending("hash",org,"admin",now.plusSeconds(601)).isEmpty());
        assertTrue(setup.consumePending("hash",org,"admin",now).isPresent());
        assertTrue(setup.consumePending("hash",org,"admin",now).isEmpty());
    }
    @Test void credentialsAreEncryptedAndNewInstancesCanReadThemWithoutOverwriting() {
        var credentials = new com.shipora.git.application.dto.GitHubAppCredentials("42","test-app","client","client-secret",
                "private-pem","webhook-secret",java.net.URI.create("https://instance.example/callback"));
        assertTrue(setup.saveCredentialsIfAbsent(credentials,now));
        assertFalse(setup.saveCredentialsIfAbsent(credentials,now));
        String value=jdbc.queryForObject("SELECT encrypted_credentials FROM git.github_app_configuration",String.class);
        assertFalse(value.contains("secret")); assertFalse(value.contains("private-pem"));
        assertEquals(credentials,setup.credentials().orElseThrow());
        var restarted=new JdbcGitHubSetupStore(jdbc,new FileCredentialCipher(directory),new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        assertEquals(credentials,restarted.credentials().orElseThrow());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM git.github_app_configuration",Integer.class));
    }
    @Test void runtimeSourcePicksUpSetupWithoutRestartAndCompletedSetupPrunesPendingStates() {
        var fallback=new com.shipora.git.configuration.GitHubProperties(null,null,null,null,null,null,"2026-03-10");
        var runtime=new com.shipora.git.configuration.GitHubCredentialsSource(setup,fallback);
        assertFalse(runtime.status().configured());
        var callback=java.net.URI.create("https://instance.example/callback");
        setup.savePending(new GitHubSetupStore.Pending("pending",org,"admin",callback,now.plusSeconds(600)),now);
        setup.saveCredentialsIfAbsent(new com.shipora.git.application.dto.GitHubAppCredentials("42","app","client","secret","pem","webhook",callback),now);
        assertTrue(runtime.status().configured()); assertEquals("DATABASE",runtime.status().source());
        assertEquals("pem",runtime.get().privateKeyPem());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM git.github_setup_states",Integer.class));
    }
    @Test void installationCannotBeMovedBetweenOrganizations() {
        var installation = connect();
        assertThrows(GitException.class, () -> store.connect(UUID.randomUUID(), new GitProvider.VerifiedInstallation("123", "owner"), now));
        assertEquals(org, store.installation(org, installation.id()).orElseThrow().organizationId());
    }
    @Test void queriesEnforceOrganizationOwnership() {
        var installation = connect(); store.replaceRepositories(installation, List.of(repo("1")), now);
        var repository = store.repositories(org, installation.id(), 0, 20).getFirst(); UUID other = UUID.randomUUID();
        assertTrue(store.installation(other, installation.id()).isEmpty()); assertTrue(store.repository(other, repository.id()).isEmpty());
        assertTrue(store.repositories(other, installation.id(), 0, 20).isEmpty());
    }
    @Test void repositorySearchMatchesAcrossPagesLiterallyAndEnforcesOwnership() {
        var installation = connect();
        var snapshot = new ArrayList<GitProvider.RepositorySnapshot>();
        for (int index = 0; index < 15; index++) snapshot.add(repo(String.valueOf(index)));
        snapshot.add(new GitProvider.RepositorySnapshot("99", "owner/zz-StoreFront", "https://github.com/owner/zz-StoreFront.git", "main", true));
        store.replaceRepositories(installation, snapshot, now);
        assertEquals("owner/zz-StoreFront", store.searchRepositories(org, installation.id(), "storefront", 0, 10).getFirst().fullName());
        assertEquals(1, store.searchRepositories(org, installation.id(), "OWNER/ZZ", 0, 10).size());
        assertTrue(store.searchRepositories(org, installation.id(), "%", 0, 10).isEmpty());
        assertTrue(store.searchRepositories(org, installation.id(), "_", 0, 10).isEmpty());
        assertEquals(6, store.searchRepositories(org, installation.id(), "owner/", 10, 10).size());
        assertTrue(store.searchRepositories(UUID.randomUUID(), installation.id(), "storefront", 0, 10).isEmpty());
        assertTrue(store.searchRepositories(org, UUID.randomUUID(), "storefront", 0, 10).isEmpty());
    }
    @Test void synchronizationPreservesRepositoryIdsAndMarksMissingRepositoriesUnavailable() {
        var installation = connect(); store.replaceRepositories(installation, List.of(repo("1"), repo("2")), now);
        var original = store.repositories(org, installation.id(), 0, 20);
        store.replaceRepositories(store.installation(org, installation.id()).orElseThrow(), List.of(repo("1")), now);
        var updated = store.repositories(org, installation.id(), 0, 20);
        assertEquals(original.getFirst().id(), updated.getFirst().id()); assertTrue(updated.getFirst().available());
        assertFalse(updated.getLast().available());
    }
    @Test void malformedSnapshotRollsBackEntireReplacement() {
        var installation = connect(); store.replaceRepositories(installation, List.of(repo("1")), now);
        var current = store.installation(org, installation.id()).orElseThrow();
        var bad = new GitProvider.RepositorySnapshot("2", "x".repeat(300), "https://github.com/owner/repo.git", "main", true);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> store.replaceRepositories(current, List.of(bad), now));
        var repos = store.repositories(org, installation.id(), 0, 20); assertEquals(1, repos.size()); assertTrue(repos.getFirst().available());
    }
    @Test void webhookDeduplicatesAndSuspendsInstallationAtomically() {
        var installation = connect(); store.replaceRepositories(installation, List.of(repo("1")), now);
        var delivery = delivery(true, false);
        assertTrue(store.recordWebhook(delivery, now)); assertFalse(store.recordWebhook(delivery, now));
        assertFalse(store.installation(org, installation.id()).orElseThrow().active());
        assertFalse(store.repositories(org, installation.id(), 0, 20).getFirst().available());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM git.webhook_deliveries", Integer.class));
    }
    @Test void webhookDuringSynchronizationPreventsStaleSnapshotRestoringAccess() {
        var installation = connect(); store.recordWebhook(delivery(false, true), now);
        assertThrows(GitException.class, () -> store.replaceRepositories(installation, List.of(repo("1")), now));
        assertTrue(store.repositories(org, installation.id(), 0, 20).isEmpty());
    }
    @Test void duplicateDeliveryCannotRepeatInvalidationAfterReconnect() {
        var installation = connect(); var event = delivery(true, false);
        store.recordWebhook(event, now); var reconnected = connect();
        assertFalse(store.recordWebhook(event, now));
        assertTrue(store.installation(org, installation.id()).orElseThrow().active());
        store.replaceRepositories(reconnected, List.of(repo("1")), now);
    }
    @Test void pushStoresCommitAndRepositoryReference() {
        var installation = connect(); store.replaceRepositories(installation, List.of(repo("1")), now);
        var delivery = new GitStore.WebhookDelivery(UUID.randomUUID().toString(), "push", "123", "1", "a".repeat(40), false, false);
        assertTrue(store.recordWebhook(delivery, now));
        assertNotNull(jdbc.queryForObject("SELECT repository_id FROM git.webhook_deliveries", UUID.class));
        assertEquals("PROCESSED", jdbc.queryForObject("SELECT status FROM git.webhook_deliveries", String.class));
    }
    private GitInstallation connect() { return store.connect(org, new GitProvider.VerifiedInstallation("123", "owner"), now); }
    @Test void callbackStateRequiresMatchingOrganizationAndCanBeConsumedOnce() {
        var pending = new ConnectionStore.Pending("hash", org, "browser", "verifier", now.plusSeconds(600));
        connections.savePending(pending);
        assertTrue(connections.consumePending("hash", UUID.randomUUID(), now).isEmpty());
        assertEquals(org, connections.consumePending("hash", org, now).orElseThrow().organizationId());
        assertTrue(connections.consumePending("hash", org, now).isEmpty());
    }
    @Test void accountChoicesRequireOrganizationAndConnectionToken() {
        connections.saveChoices(org, "browser", List.of(new GitProvider.VerifiedInstallation("123", "owner")), now.plusSeconds(600));
        var choice = connections.choices(org, "browser", now).getFirst();
        assertTrue(connections.choices(UUID.randomUUID(), "browser", now).isEmpty());
        assertTrue(connections.choices(org, "other", now).isEmpty());
        assertTrue(connections.consumeChoice(UUID.randomUUID(), "browser", choice.id(), now).isEmpty());
        assertTrue(connections.consumeChoice(org, "other", choice.id(), now).isEmpty());
        assertEquals("123", connections.consumeChoice(org, "browser", choice.id(), now).orElseThrow().externalId());
    }
    @Test void accountSelectionConsumesAllChoicesFromThatConnection() {
        connections.saveChoices(org, "browser", List.of(new GitProvider.VerifiedInstallation("123", "owner"),
                new GitProvider.VerifiedInstallation("456", "organization")), now.plusSeconds(600));
        var choices = connections.choices(org, "browser", now);
        assertTrue(connections.consumeChoice(org, "browser", choices.getFirst().id(), now).isPresent());
        assertTrue(connections.consumeChoice(org, "browser", choices.getLast().id(), now).isEmpty());
    }
    @Test void expiredConnectionStatesAndChoicesCannotBeUsed() {
        connections.savePending(new ConnectionStore.Pending("hash", org, "browser", "verifier", now.minusSeconds(1)));
        assertTrue(connections.consumePending("hash", org, now).isEmpty());
        connections.saveChoices(org, "browser", List.of(new GitProvider.VerifiedInstallation("123", "owner")), now.plusSeconds(1));
        var choice = connections.choices(org, "browser", now).getFirst();
        assertTrue(connections.consumeChoice(org, "browser", choice.id(), now.plusSeconds(2)).isEmpty());
    }
    @Test void replacingConnectionStateInvalidatesPreviousStateAndChoices() {
        connections.savePending(new ConnectionStore.Pending("old", org, "browser", "verifier", now.plusSeconds(600)));
        connections.saveChoices(org, "browser", List.of(new GitProvider.VerifiedInstallation("123", "owner")), now.plusSeconds(600));
        connections.savePending(new ConnectionStore.Pending("new", org, "browser", "verifier", now.plusSeconds(600)));
        assertTrue(connections.consumePending("old", org, now).isEmpty());
        assertTrue(connections.choices(org, "browser", now).isEmpty());
        assertTrue(connections.consumePending("new", org, now).isPresent());
    }
    @Test void concurrentSelectionsCannotLinkTwoAccountsFromOneFlow() throws Exception {
        connections.saveChoices(org, "browser", List.of(new GitProvider.VerifiedInstallation("123", "owner"),
                new GitProvider.VerifiedInstallation("456", "organization")), now.plusSeconds(600));
        var choices = connections.choices(org, "browser", now);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> connections.consumeChoice(org, "browser", choices.getFirst().id(), now));
            var second = executor.submit(() -> connections.consumeChoice(org, "browser", choices.getLast().id(), now));
            int consumed = (first.get().isPresent() ? 1 : 0) + (second.get().isPresent() ? 1 : 0);
            assertEquals(1, consumed);
        }
    }
    private GitProvider.RepositorySnapshot repo(String id) {
        return new GitProvider.RepositorySnapshot(id, "owner/repo" + id, "https://github.com/owner/repo" + id + ".git", "main", true);
    }
    @Test void signedBranchPushWritesTenantScopedOutboxInTheSameTransaction(){
        var installation=connect();store.replaceRepositories(installation,List.of(repo("1")),now);
        var repository=store.repositories(org,installation.id(),0,20).getFirst();String event=UUID.randomUUID().toString();
        var push=new GitStore.WebhookDelivery(event,"push","123","1","a".repeat(40),false,false,"main");
        assertTrue(store.recordWebhook(push,now));assertFalse(store.recordWebhook(push,now));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM git.deployment_push_outbox",Integer.class));
        assertEquals(org,jdbc.queryForObject("SELECT organization_id FROM git.deployment_push_outbox",UUID.class));
        assertEquals(repository.id(),jdbc.queryForObject("SELECT repository_id FROM git.deployment_push_outbox",UUID.class));
    }
    @Test void deletedTagsUnknownAndRevokedRepositoriesDoNotProducePushJobs(){
        var installation=connect();store.replaceRepositories(installation,List.of(repo("1")),now);
        store.recordWebhook(new GitStore.WebhookDelivery(UUID.randomUUID().toString(),"push","123","1","0".repeat(40),false,false,"main"),now);
        store.recordWebhook(new GitStore.WebhookDelivery(UUID.randomUUID().toString(),"push","123","1","a".repeat(40),false,false,null),now);
        store.recordWebhook(new GitStore.WebhookDelivery(UUID.randomUUID().toString(),"push","123","999","a".repeat(40),false,false,"main"),now);
        store.recordWebhook(delivery(true,false),now);
        store.recordWebhook(new GitStore.WebhookDelivery(UUID.randomUUID().toString(),"push","123","1","a".repeat(40),false,false,"main"),now);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM git.deployment_push_outbox",Integer.class));
    }
    private GitStore.WebhookDelivery delivery(boolean disable, boolean repositories) {
        return new GitStore.WebhookDelivery(UUID.randomUUID().toString(), "installation", "123", null, null, disable, repositories);
    }
}

