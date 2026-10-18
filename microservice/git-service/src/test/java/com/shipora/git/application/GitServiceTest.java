package com.shipora.git.application;

import com.shipora.git.application.port.out.*;
import com.shipora.git.application.service.GitService;
import com.shipora.git.domain.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitServiceTest {
    private final GitStore store = mock(GitStore.class);
    private final GitProvider provider = mock(GitProvider.class);
    private final Instant now = Instant.parse("2026-10-18T00:00:00Z");
    private final GitService service = new GitService(store, provider, Clock.fixed(now, ZoneOffset.UTC));
    private final UUID org = UUID.randomUUID(), id = UUID.randomUUID();
    private final GitInstallation installation = new GitInstallation(id, org, "123", "owner", true, 0, now);

    @Test void connectionUsesRandomStateHashAndPkceWithTenMinuteExpiry() {
        when(provider.authorizationUrl(anyString(), anyString())).thenReturn("https://github.com/authorize");
        var result = service.startConnection(org, "123");
        var state = ArgumentCaptor.forClass(GitStore.ConnectionState.class);
        verify(store).saveState(state.capture());
        var raw = ArgumentCaptor.forClass(String.class); var challenge = ArgumentCaptor.forClass(String.class);
        verify(provider).authorizationUrl(raw.capture(), challenge.capture());
        assertEquals(43, raw.getValue().length()); assertEquals(43, challenge.getValue().length());
        assertEquals(GitService.hash(raw.getValue()), state.getValue().hash());
        assertNotEquals(raw.getValue(), state.getValue().codeVerifier());
        assertEquals(org, state.getValue().organizationId());
        assertEquals(now.plusSeconds(600), result.expiresAt());
        assertFalse(state.getValue().toString().contains(state.getValue().codeVerifier()));
    }
    @Test void rejectsInvalidExternalIdsBeforeWritingState() {
        for (String input : List.of("0", "-1", "https://github.com", "1/2", " ")) {
            assertThrows(GitException.class, () -> service.startConnection(org, input));
        }
        verifyNoInteractions(store, provider);
    }
    @Test void callbackVerifiesGithubBeforeLinkingStoredOrganization() {
        String raw = "a".repeat(43);
        var pending = new GitStore.ConnectionState(GitService.hash(raw), org, "123", "verifier", now.plusSeconds(600));
        var verified = new GitProvider.VerifiedInstallation("123", "owner");
        when(store.consumeState(pending.hash(), now)).thenReturn(Optional.of(pending));
        when(provider.verifyInstallation("123", "code", "verifier")).thenReturn(verified);
        when(store.connect(org, verified, now)).thenReturn(installation);
        assertEquals(id, service.completeConnection(raw, "code").id());
        var order = inOrder(store, provider);
        order.verify(store).consumeState(pending.hash(), now);
        order.verify(provider).verifyInstallation("123", "code", "verifier");
        order.verify(store).connect(org, verified, now);
    }
    @Test void expiredOrReplayedCallbackNeverCallsGithub() {
        when(store.consumeState(anyString(), eq(now))).thenReturn(Optional.empty());
        assertThrows(GitException.class, () -> service.completeConnection("a".repeat(43), "code"));
        verifyNoInteractions(provider);
    }
    @Test void rejectsProviderInstallationMismatch() {
        when(store.consumeState(anyString(), eq(now))).thenReturn(Optional.of(
                new GitStore.ConnectionState("hash", org, "123", "verifier", now.plusSeconds(1))));
        when(provider.verifyInstallation("123", "code", "verifier")).thenReturn(new GitProvider.VerifiedInstallation("456", "owner"));
        assertThrows(GitException.class, () -> service.completeConnection("a".repeat(43), "code"));
        verify(store, never()).connect(any(), any(), any());
    }
    @Test void otherOrganizationCannotSynchronizeAnInstallation() {
        when(store.installation(org, id)).thenReturn(Optional.empty());
        var error = assertThrows(GitException.class, () -> service.synchronize(org, id));
        assertEquals(GitException.Kind.NOT_FOUND, error.getKind()); verifyNoInteractions(provider);
    }
    @Test void inactiveInstallationRequiresReconnection() {
        when(store.installation(org, id)).thenReturn(Optional.of(new GitInstallation(id, org, "123", "owner", false, 1, now)));
        assertThrows(GitException.class, () -> service.synchronize(org, id)); verifyNoInteractions(provider);
    }
    @Test void providerFailureDoesNotReplaceExistingSnapshot() {
        when(store.installation(org, id)).thenReturn(Optional.of(installation));
        when(provider.repositories("123")).thenThrow(new GitException(GitException.Kind.PROVIDER, "unavailable"));
        assertThrows(GitException.class, () -> service.synchronize(org, id));
        verify(store, never()).replaceRepositories(any(), any(), any());
    }
    @Test void successfulSyncPersistsCompleteSnapshot() {
        when(store.installation(org, id)).thenReturn(Optional.of(installation));
        var snapshot = List.of(new GitProvider.RepositorySnapshot("1", "owner/repo", "https://github.com/owner/repo.git", "main", true));
        when(provider.repositories("123")).thenReturn(snapshot);
        assertEquals(1, service.synchronize(org, id)); verify(store).replaceRepositories(installation, snapshot, now);
    }
    @Test void paginationRejectsInvalidAndOverflowValues() {
        assertThrows(GitException.class, () -> service.installations(org, -1, 20));
        assertThrows(GitException.class, () -> service.installations(org, 0, 101));
        assertThrows(GitException.class, () -> service.installations(org, Integer.MAX_VALUE, 100));
        verifyNoInteractions(store);
    }
}
