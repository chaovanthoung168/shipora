package com.shipora.git.application;

import com.shipora.git.application.port.in.GitHubSetupUseCases.*;
import com.shipora.git.application.port.out.*;
import com.shipora.git.application.dto.GitHubAppCredentials;
import com.shipora.git.application.service.*;
import com.shipora.git.domain.GitException;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubSetupServiceTest {
    final GitHubSetupStore store = mock(GitHubSetupStore.class);
    final GitHubRegistration provider = mock(GitHubRegistration.class);
    final SetupManifest manifest = mock(SetupManifest.class);
    final UUID org = UUID.randomUUID();
    final Instant now = Instant.parse("2026-10-18T00:00:00Z");
    final URI callback = URI.create("https://shipora.example/api/git/github/callback");
    final Status empty = new Status(false, true, "NONE", null, null);
    final GitHubSetupService service = new GitHubSetupService(store, provider, manifest, () -> empty, Clock.fixed(now, ZoneOffset.UTC));
    final String state = "s_" + "a".repeat(43);
    @BeforeEach void setup() { when(manifest.create(anyString())).thenReturn(new SetupManifest.Manifest("{\"name\":\"app\"}", callback)); }
    @Test void registrationUsesPostManifestAndStoresOnlyStateHash() {
        var response = service.start(org, "admin", null, null);
        assertEquals("POST", response.method()); assertTrue(response.formFields().containsKey("manifest"));
        String raw = response.actionUrl().split("state=")[1]; assertTrue(raw.matches("s_[A-Za-z0-9_-]{43}"));
        var captor = ArgumentCaptor.forClass(GitHubSetupStore.Pending.class);
        verify(store).savePending(captor.capture(), eq(now));
        assertEquals(GitService.hash(raw), captor.getValue().stateHash());
        assertEquals("admin", captor.getValue().actor()); assertEquals(callback, captor.getValue().callbackUrl());
        assertFalse(response.toString().contains(raw)); assertEquals(now.plusSeconds(600), response.expiresAt());
    }
    @Test void organizationRegistrationUsesGithubOwnedOrigin() {
        assertTrue(service.start(org,"admin","Shipora test","my-org").actionUrl().startsWith("https://github.com/organizations/my-org/settings/apps/new?"));
        assertThrows(GitException.class,()->service.start(org,"admin",null,"../attacker"));
        assertThrows(GitException.class,()->service.start(org,"admin","<script>",null));
    }
    @Test void configuredInstanceCannotBeOverwritten() {
        var configured = new GitHubSetupService(store,provider,manifest,()->new Status(true,false,"DATABASE","1","url"),Clock.systemUTC());
        assertThrows(GitException.class,()->configured.start(org,"admin",null,null)); verifyNoInteractions(store,provider,manifest);
    }
    @Test void callbackIsBoundToAdministratorAndOrganizationBeforeExchangingCode() {
        when(store.consumePending(GitService.hash(state),org,"other",now)).thenReturn(Optional.empty());
        assertThrows(GitException.class,()->service.complete(org,"other",state,"code")); verifyNoInteractions(provider);
    }
    @Test void expiredOrReplayedCallbackNeverExchangesCode() {
        when(store.consumePending(anyString(),eq(org),eq("admin"),eq(now))).thenReturn(Optional.empty());
        assertThrows(GitException.class,()->service.complete(org,"admin",state,"code")); verifyNoInteractions(provider);
    }
    @Test void successfulRegistrationPersistsCredentialsAndReturnsOnlyPublicMetadata() {
        pending();
        var credentials = credentials();
        when(provider.convert("code",callback)).thenReturn(credentials);
        when(store.saveCredentialsIfAbsent(credentials,now)).thenReturn(true);
        var result = service.complete(org,"admin",state,"code");
        assertTrue(result.configured()); assertEquals("42",result.appId());
        assertEquals("https://github.com/apps/shipora-test/installations/new",result.installationUrl());
        assertFalse(result.toString().contains("private")); assertFalse(credentials.toString().contains("secret"));
    }
    @Test void racingRegistrationCannotReplaceExistingConfiguration() {
        pending(); when(provider.convert("code",callback)).thenReturn(credentials());
        assertEquals(GitException.Kind.CONFLICT,assertThrows(GitException.class,()->service.complete(org,"admin",state,"code")).getKind());
    }
    @Test void malformedOrCancelledCallbacksAreRejectedBeforeStorageOrProvider() {
        for(String code : new String[]{"", "../bad", "a".repeat(513)})
            assertThrows(GitException.class,()->service.complete(org,"admin",state,code));
        assertThrows(GitException.class,()->service.complete(org,"admin","invalid","code")); verifyNoInteractions(store,provider);
    }
    void pending(){ when(store.consumePending(GitService.hash(state),org,"admin",now))
        .thenReturn(Optional.of(new GitHubSetupStore.Pending(GitService.hash(state),org,"admin",callback,now.plusSeconds(600)))); }
    GitHubAppCredentials credentials(){return new GitHubAppCredentials("42","shipora-test","client","secret","private-pem","webhook-secret",callback);}
}
