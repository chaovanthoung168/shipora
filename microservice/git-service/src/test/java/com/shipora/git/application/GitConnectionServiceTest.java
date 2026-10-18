package com.shipora.git.application;

import com.shipora.git.application.port.in.GitConnectionUseCases;
import com.shipora.git.application.port.out.*;
import com.shipora.git.application.service.*;
import com.shipora.git.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitConnectionServiceTest {
    private final ConnectionStore states = mock(ConnectionStore.class);
    private final GitStore store = mock(GitStore.class);
    private final GitProvider provider = mock(GitProvider.class);
    private final Instant now = Instant.parse("2026-10-18T00:00:00Z");
    private final GitConnectionService service = new GitConnectionService(states, store, provider, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID org = UUID.randomUUID();
    private final String token = "t".repeat(43), state = "c_" + "a".repeat(43);

    @Test void startsWithoutInstallationIdAndReturnsASeparateConnectionProof() {
        when(provider.authorizationUrl(anyString(), anyString())).thenReturn("https://github.com/login/oauth/authorize");
        var result = service.start(org);
        assertEquals(now.plusSeconds(600), result.expiresAt()); assertTrue(result.connectionToken().matches("[A-Za-z0-9_-]{43}"));
        var pending = ArgumentCaptor.forClass(ConnectionStore.Pending.class); verify(states).savePending(pending.capture());
        assertEquals(org, pending.getValue().organizationId()); assertEquals(GitService.hash(result.connectionToken()), pending.getValue().tokenHash());
        var raw = ArgumentCaptor.forClass(String.class); verify(provider).authorizationUrl(raw.capture(), anyString());
        assertTrue(raw.getValue().matches("c_[A-Za-z0-9_-]{43}")); assertEquals(GitService.hash(raw.getValue()), pending.getValue().stateHash());
        assertFalse(raw.getValue().contains(result.connectionToken()));
        assertEquals("Pending[redacted]", pending.getValue().toString()); assertEquals("DiscoveryConnectionResult[redacted]", result.toString());
    }
    @Test void callbackStoresChoicesForTheAuthenticatedInitiatingOrganization() {
        var pending = new ConnectionStore.Pending(GitService.hash(state), org, GitService.hash(token), "verifier", now.plusSeconds(600));
        when(states.consumePending(pending.stateHash(), org, now)).thenReturn(Optional.of(pending));
        var accounts = List.of(new GitProvider.VerifiedInstallation("123", "owner"));
        when(provider.discoverInstallations("code", "verifier")).thenReturn(new GitProvider.InstallationDiscovery(accounts, "https://github.com/apps/shipora/installations/new"));
        var result = service.complete(org, state, "code");
        assertTrue(result.authorized()); assertEquals(1, result.availableAccounts());
        verify(states).saveChoices(org, GitService.hash(token), accounts, now.plusSeconds(600)); verifyNoInteractions(store);
    }
    @Test void anotherOrganizationCannotExchangeTheCode() {
        UUID other = UUID.randomUUID();
        when(states.consumePending(GitService.hash(state), other, now)).thenReturn(Optional.empty());
        assertThrows(GitException.class, () -> service.complete(other, state, "code")); verifyNoInteractions(provider);
    }
    @Test void expiredOrReplayedStateCannotExchangeTheCode() {
        when(states.consumePending(anyString(), eq(org), any())).thenReturn(Optional.empty());
        assertThrows(GitException.class, () -> service.complete(org, state, "code")); verifyNoInteractions(provider);
    }
    @Test void cancelledOrInvalidAuthorizationDoesNotConsumeState() {
        assertThrows(GitException.class, () -> service.complete(org, state, null));
        assertThrows(GitException.class, () -> service.complete(org, "a".repeat(43), "code")); verifyNoInteractions(states, provider);
    }
    @Test void selectionRechecksProviderAccessBeforeLinking() {
        UUID choiceId = UUID.randomUUID(); var account = new GitProvider.VerifiedInstallation("123", "owner");
        when(states.consumeChoice(org, GitService.hash(token), choiceId, now)).thenReturn(Optional.of(account));
        when(store.connect(org, account, now)).thenReturn(new GitInstallation(UUID.randomUUID(), org, "123", "owner", true, 0, now));
        assertTrue(service.select(org, token, choiceId).active());
        var order = inOrder(states, provider, store); order.verify(states).consumeChoice(org, GitService.hash(token), choiceId, now);
        order.verify(provider).requireActiveInstallation("123"); order.verify(store).connect(org, account, now);
    }
    @Test void revokedInstallationCannotBeLinked() {
        UUID choiceId = UUID.randomUUID();
        when(states.consumeChoice(org, GitService.hash(token), choiceId, now)).thenReturn(Optional.of(new GitProvider.VerifiedInstallation("123", "owner")));
        doThrow(new GitException(GitException.Kind.FORBIDDEN, "revoked")).when(provider).requireActiveInstallation("123");
        assertThrows(GitException.class, () -> service.select(org, token, choiceId)); verifyNoInteractions(store);
    }
    @Test void choiceRequiresTheOriginalConnectionTokenAndOrganization() {
        UUID choiceId = UUID.randomUUID();
        when(states.consumeChoice(org, GitService.hash(token), choiceId, now)).thenReturn(Optional.empty());
        assertThrows(GitException.class, () -> service.select(org, token, choiceId)); verifyNoInteractions(provider, store);
    }
    @Test void accountsUseScopedTokenHashWithoutAnyHttpSession() {
        when(states.choices(org, GitService.hash(token), now)).thenReturn(List.of(new GitConnectionUseCases.AccountChoice(UUID.randomUUID(), "owner")));
        assertEquals(1, service.choices(org, token).size());
        verify(states).choices(org, GitService.hash(token), now);
    }
    @Test void invalidConnectionTokensAreRejectedBeforeStorage() {
        for (String invalid : Arrays.asList(null, "", "token", "a".repeat(44), "!".repeat(43))) {
            assertThrows(GitException.class, () -> service.choices(org, invalid));
        }
        verifyNoInteractions(states);
    }
}
