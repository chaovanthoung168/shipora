package com.shipora.git.adapter.in.web;

import com.shipora.git.adapter.in.security.CurrentOrganization;
import com.shipora.git.application.dto.*;
import com.shipora.git.application.port.in.GitUseCases;
import com.shipora.git.application.port.in.GitConnectionUseCases;
import com.shipora.git.application.port.in.GitHubSetupUseCases;
import com.shipora.git.configuration.SecurityConfiguration;
import com.shipora.git.domain.GitException;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GitWebSecurityTest {
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private GitUseCases useCases;
    private SignedWebhookHandler webhook;
    private GitConnectionUseCases connectionUseCases;

    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("local");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "shipora.local.username=developer",
                "shipora.local.password=test-only-password", "shipora.local.organization-id=" + ORG);
        context.register(TestConfiguration.class, SecurityConfiguration.class, CurrentOrganization.class,
                GitController.class, GitHubSetupController.class, GitExceptionHandler.class); context.refresh();
        useCases = context.getBean(GitUseCases.class); webhook = context.getBean(SignedWebhookHandler.class);
        connectionUseCases = context.getBean(GitConnectionUseCases.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close() { if (context != null) context.close(); }

    @Test void missingCredentialsReturns401() throws Exception {
        mvc.perform(get("/api/git/installations")).andExpect(status().isUnauthorized()); verifyNoInteractions(useCases);
    }
    @Test void setupStatusIsAuthenticatedAndReturnsNoSecrets() throws Exception {
        var setup=context.getBean(GitHubSetupUseCases.class);
        when(setup.status()).thenReturn(new GitHubSetupUseCases.Status(false,true,"NONE",null,null));
        mvc.perform(get("/api/git/setup/github")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/git/setup/github").with(httpBasic("developer","test-only-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("setupRequired").value(true))
                .andExpect(jsonPath("clientSecret").doesNotExist()).andExpect(jsonPath("privateKeyPem").doesNotExist());
    }
    @Test void instanceSetupRequiresAdminBeyondNormalGitManagementRole() throws Exception {
        var member=user("member").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_GIT_MANAGE"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ORG_"+ORG));
        mvc.perform(post("/api/git/setup/github").with(member).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/git/setup/github/callback").with(member).param("state","state").param("code","code")).andExpect(status().isForbidden());
        mvc.perform(post("/api/git/setup/github/callback").with(member).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(GitHubSetupUseCases.class));
    }
    @Test void adminGetsJsonManifestFormWithoutBackendUiOrCookies() throws Exception {
        var setup=context.getBean(GitHubSetupUseCases.class);
        when(setup.start(ORG,"developer",null,null)).thenReturn(new GitHubSetupUseCases.Registration(
                "https://github.com/settings/apps/new?state=state","POST",Map.of("manifest","{}"),Instant.EPOCH));
        mvc.perform(post("/api/git/setup/github").with(httpBasic("developer","test-only-password"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isCreated())
                .andExpect(jsonPath("formFields.manifest").value("{}")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void setupCallbackRequiresAuthenticationEvenWithValidLookingState() throws Exception {
        mvc.perform(get("/api/git/setup/github/callback").param("state","s_"+"a".repeat(43)).param("code","code"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(context.getBean(GitHubSetupUseCases.class));
    }
    @Test void setupCallbackReturnsPublicMetadataForOriginalAdministrator() throws Exception {
        var setup=context.getBean(GitHubSetupUseCases.class);
        when(setup.complete(ORG,"developer","s_"+"a".repeat(43),"code"))
                .thenReturn(new GitHubSetupUseCases.Completion(true,"42","https://github.com/apps/test/installations/new"));
        mvc.perform(get("/api/git/setup/github/callback").with(httpBasic("developer","test-only-password"))
                .param("state","s_"+"a".repeat(43)).param("code","code")).andExpect(status().isOk())
                .andExpect(jsonPath("configured").value(true)).andExpect(jsonPath("privateKeyPem").doesNotExist())
                .andExpect(header().doesNotExist("Location"));
    }
    @Test void nextJsCanCompleteSetupThroughAuthenticatedPost() throws Exception {
        var setup=context.getBean(GitHubSetupUseCases.class);
        when(setup.complete(ORG,"developer","s_"+"a".repeat(43),"code"))
                .thenReturn(new GitHubSetupUseCases.Completion(true,"42","https://github.com/apps/test/installations/new"));
        mvc.perform(post("/api/git/setup/github/callback").with(httpBasic("developer","test-only-password"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"s_"+"a".repeat(43)+"\",\"code\":\"code\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("appId").value("42"));
    }
    @Test void wrongPasswordReturns401() throws Exception {
        mvc.perform(get("/api/git/installations").with(httpBasic("developer", "wrong"))).andExpect(status().isUnauthorized());
    }
    @Test void validBasicAuthUsesTrustedOrganization() throws Exception {
        when(useCases.installations(ORG, 0, 20)).thenReturn(List.of());
        mvc.perform(get("/api/git/installations").with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(useCases).installations(ORG, 0, 20);
    }
    @Test void readOnlyRoleCannotConnectOrSynchronize() throws Exception {
        var reader = user("reader").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_GIT_ACCESS"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ORG_" + ORG));
        mvc.perform(post("/api/git/connections").with(reader).contentType(MediaType.APPLICATION_JSON).content("{\"installationId\":\"123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/git/installations/" + UUID.randomUUID() + "/sync").with(reader)).andExpect(status().isForbidden());
        verifyNoInteractions(useCases);
    }
    @Test void invalidConnectionIdReturns400() throws Exception {
        mvc.perform(post("/api/git/connections").with(httpBasic("developer", "test-only-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"installationId\":\"../123\"}"))
                .andExpect(status().isBadRequest()); verifyNoInteractions(useCases);
    }
    @Test void callbackAcceptsStateWithoutBasicAuthAndDoesNotCacheResult() throws Exception {
        String state = "a".repeat(43);
        when(useCases.completeConnection(state, "code")).thenReturn(new InstallationResult(UUID.randomUUID(), "GITHUB", "123", "owner", true, Instant.EPOCH));
        mvc.perform(get("/api/git/github/callback").param("state", state).param("code", "code"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(useCases).completeConnection(state, "code");
    }
    @Test void webhookIsPublicButSignatureFailureReturns403() throws Exception {
        when(webhook.handle(isNull(), isNull(), isNull(), any())).thenThrow(new GitException(GitException.Kind.FORBIDDEN, "Invalid signature."));
        mvc.perform(post("/api/git/webhooks/github").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void legacyStateThatRandomlyStartsWithDiscoveryPrefixStillUsesLegacyCallback() throws Exception {
        String state = "c_" + "a".repeat(41);
        when(useCases.completeConnection(state, "code")).thenReturn(new InstallationResult(UUID.randomUUID(), "GITHUB", "123", "owner", true, Instant.EPOCH));
        mvc.perform(get("/api/git/github/callback").param("state", state).param("code", "code"))
                .andExpect(status().isOk());
        verify(useCases).completeConnection(state, "code"); verifyNoInteractions(connectionUseCases);
    }
    @Test void anotherOrganizationsRepositoryIs404() throws Exception {
        UUID repo = UUID.randomUUID();
        when(useCases.repository(ORG, repo)).thenThrow(new GitException(GitException.Kind.NOT_FOUND, "Repository not found."));
        mvc.perform(get("/api/git/repositories/" + repo).with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("detail").value("Repository not found."));
    }
    @Test void malformedUuidReturns400() throws Exception {
        mvc.perform(get("/api/git/repositories/not-a-uuid").with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isBadRequest());
    }
    @Test void missingOrganizationIdentityCannotRead() throws Exception {
        mvc.perform(get("/api/git/installations").with(user("reader").roles("GIT_ACCESS"))).andExpect(status().isForbidden());
        verifyNoInteractions(useCases);
    }
    @Test void backendDoesNotServeTheRemovedUiOrAssets() throws Exception {
        mvc.perform(get("/git").with(httpBasic("developer", "test-only-password"))).andExpect(status().isForbidden());
        mvc.perform(get("/git/connect.js").with(httpBasic("developer", "test-only-password"))).andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertFalse(new org.springframework.core.io.ClassPathResource("static/git/connect.html").exists());
        org.junit.jupiter.api.Assertions.assertFalse(new org.springframework.core.io.ClassPathResource("static/git/connect.js").exists());
        org.junit.jupiter.api.Assertions.assertFalse(new org.springframework.core.io.ClassPathResource("static/git/connect.css").exists());
    }
    @Test void emptyObjectStartsDiscoveryWithoutSessionCookiesOrCsrf() throws Exception {
        when(connectionUseCases.start(ORG)).thenReturn(new DiscoveryConnectionResult("https://github.com/login/oauth/authorize", Instant.EPOCH, "t".repeat(43)));
        var result = mvc.perform(post("/api/git/connections").with(httpBasic("developer", "test-only-password"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("connectionToken").value("t".repeat(43)))
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        org.junit.jupiter.api.Assertions.assertNull(result.getRequest().getSession(false)); verifyNoInteractions(useCases);
    }
    @Test void omittedBodyAlsoStartsDiscovery() throws Exception {
        when(connectionUseCases.start(ORG)).thenReturn(new DiscoveryConnectionResult("https://github.com/login/oauth/authorize", Instant.EPOCH, "t".repeat(43)));
        mvc.perform(post("/api/git/connections").with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isCreated()); verify(connectionUseCases).start(ORG);
    }
    @Test void explicitInstallationIdRetainsOriginalApiContract() throws Exception {
        when(useCases.startConnection(ORG, "123")).thenReturn(new ConnectionResult("https://github.com/login/oauth/authorize", Instant.EPOCH));
        mvc.perform(post("/api/git/connections").with(httpBasic("developer", "test-only-password"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"installationId\":\"123\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("connectionToken").doesNotExist()); verifyNoInteractions(connectionUseCases);
    }
    @Test void discoveryCallbackRequiresShiporaAuthentication() throws Exception {
        mvc.perform(get("/api/git/github/callback").param("state", "c_" + "a".repeat(43)).param("code", "code"))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(connectionUseCases);
    }
    @Test void discoveryCallbackReturnsJsonWithoutRedirectOrBrowserSession() throws Exception {
        String state = "c_" + "a".repeat(43);
        when(connectionUseCases.complete(ORG, state, "code")).thenReturn(new ConnectionAuthorizationResult(true, 1,
                "https://github.com/apps/shipora/installations/new", Instant.EPOCH));
        var result = mvc.perform(get("/api/git/github/callback").param("state", state).param("code", "code")
                .with(httpBasic("developer", "test-only-password"))).andExpect(status().isOk())
                .andExpect(jsonPath("authorized").value(true)).andExpect(jsonPath("availableAccounts").value(1))
                .andExpect(header().doesNotExist("Location")).andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        org.junit.jupiter.api.Assertions.assertNull(result.getRequest().getSession(false));
    }
    @Test void nextJsCanExchangeCallbackCodeUsingAuthenticatedPost() throws Exception {
        String state = "c_" + "a".repeat(43);
        when(connectionUseCases.complete(ORG, state, "code")).thenReturn(new ConnectionAuthorizationResult(true, 1,
                "https://github.com/apps/shipora/installations/new", Instant.EPOCH));
        mvc.perform(post("/api/git/github/callback").with(httpBasic("developer", "test-only-password"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"" + state + "\",\"code\":\"code\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("authorized").value(true)).andExpect(header().doesNotExist("Location"));
    }
    @Test void postCallbackRejectsAnonymousRequests() throws Exception {
        mvc.perform(post("/api/git/github/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"c_" + "a".repeat(43) + "\",\"code\":\"code\"}"))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(connectionUseCases);
    }
    @Test void accountDiscoveryRequiresConnectionTokenHeader() throws Exception {
        mvc.perform(get("/api/git/connections/accounts").with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isBadRequest()); verifyNoInteractions(connectionUseCases);
    }
    @Test void accountDiscoveryUsesAuthenticatedOrganizationAndClientProof() throws Exception {
        UUID choice = UUID.randomUUID(); String token = "t".repeat(43);
        when(connectionUseCases.choices(ORG, token)).thenReturn(List.of(new GitConnectionUseCases.AccountChoice(choice, "owner")));
        mvc.perform(get("/api/git/connections/accounts").with(httpBasic("developer", "test-only-password"))
                .header("X-Git-Connection-Token", token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].accountName").value("owner"))
                .andExpect(header().doesNotExist("Set-Cookie")); verify(connectionUseCases).choices(ORG, token);
    }
    @Test void postmanCanSelectAnAccountWithoutCookiesOrCsrf() throws Exception {
        UUID choice = UUID.randomUUID(); String token = "t".repeat(43);
        when(connectionUseCases.select(ORG, token, choice)).thenReturn(new InstallationResult(UUID.randomUUID(), "GITHUB", "123", "owner", true, Instant.EPOCH));
        mvc.perform(post("/api/git/connections/accounts").with(httpBasic("developer", "test-only-password"))
                .header("X-Git-Connection-Token", token).contentType(MediaType.APPLICATION_JSON).content("{\"choiceId\":\"" + choice + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("active").value(true)).andExpect(header().doesNotExist("Set-Cookie"));
    }
    @Test void readOnlyUserCannotAuthorizeOrSelectAccounts() throws Exception {
        var reader = user("reader").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_GIT_ACCESS"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ORG_" + ORG));
        mvc.perform(get("/api/git/github/callback").with(reader).param("state", "c_" + "a".repeat(43)).param("code", "code"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/git/connections/accounts").with(reader).header("X-Git-Connection-Token", "t".repeat(43))
                .contentType(MediaType.APPLICATION_JSON).content("{\"choiceId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden()); verifyNoInteractions(connectionUseCases);
    }
    @Test void failedAuthorizationReturnsProblemJsonRatherThanHtmlRedirect() throws Exception {
        String state = "c_" + "a".repeat(43);
        when(connectionUseCases.complete(ORG, state, "code")).thenThrow(new GitException(GitException.Kind.INVALID, "Connection expired."));
        mvc.perform(get("/api/git/github/callback").with(httpBasic("developer", "test-only-password"))
                .param("state", state).param("code", "code")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("detail").value("Connection expired.")).andExpect(header().doesNotExist("Location"));
    }
    @org.springframework.boot.test.context.TestConfiguration @EnableWebMvc @EnableWebSecurity
    static class TestConfiguration {
        @Bean GitUseCases useCases() { return mock(GitUseCases.class); }
        @Bean SignedWebhookHandler webhook() { return mock(SignedWebhookHandler.class); }
        @Bean GitConnectionUseCases connectionUseCases() { return mock(GitConnectionUseCases.class); }
        @Bean GitHubSetupUseCases setupUseCases() { return mock(GitHubSetupUseCases.class); }
    }
}

