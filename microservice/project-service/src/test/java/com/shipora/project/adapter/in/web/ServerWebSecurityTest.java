package com.shipora.project.adapter.in.web;

import com.shipora.project.adapter.in.security.CurrentOrganization;
import com.shipora.project.adapter.in.web.controller.ProjectController;
import com.shipora.project.adapter.in.web.controller.ServerController;
import com.shipora.project.adapter.in.web.controller.ServerConnectionController;
import com.shipora.project.adapter.in.web.exception.ProjectExceptionHandler;
import com.shipora.project.adapter.in.web.exception.ServerExceptionHandler;
import com.shipora.project.adapter.in.web.mapper.ProjectWebMapper;
import com.shipora.project.adapter.in.web.mapper.ServerWebMapper;
import com.shipora.project.application.dto.ServerResult;
import com.shipora.project.application.dto.ServerConnectionResult;
import com.shipora.project.application.port.in.ServerConnectionUseCases;
import com.shipora.project.application.port.in.ProjectUseCases;
import com.shipora.project.application.port.in.ServerUseCases;
import com.shipora.project.configuration.SecurityConfiguration;
import com.shipora.project.domain.exception.ServerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ServerWebSecurityTest {
    private static final UUID ORG =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SERVER =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String FINGERPRINT = "SHA256:"
            + Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);

    private AnnotationConfigWebApplicationContext context;
    private ServerUseCases useCases;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("local");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "shipora.local.username=developer",
                "shipora.local.password=test-only-password",
                "shipora.local.organization-id=" + ORG);
        context.register(TestConfiguration.class, SecurityConfiguration.class,
                CurrentOrganization.class, ServerController.class,
                ServerWebMapper.class, ServerExceptionHandler.class,
                ProjectController.class, ProjectWebMapper.class,
                ProjectExceptionHandler.class, ServerConnectionController.class);
        context.refresh();
        useCases = context.getBean(ServerUseCases.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void rejectsMissingCredentials() throws Exception {
        mvc.perform(get("/api/servers")).andExpect(status().isUnauthorized());
        verifyNoInteractions(useCases);
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        mvc.perform(get("/api/servers")
                        .with(httpBasic("developer", "wrong-password")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(useCases);
    }

    @Test
    void requiresServerPermissionEvenForAuthenticatedProjectUser() throws Exception {
        mvc.perform(get("/api/servers").with(user("project-only").authorities(
                        new SimpleGrantedAuthority("ROLE_PROJECT_ACCESS"),
                        new SimpleGrantedAuthority("ORG_" + ORG))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(useCases);
    }

    @Test
    void preservesExistingProjectEndpointAccess() throws Exception {
        ProjectUseCases projects = context.getBean(ProjectUseCases.class);
        when(projects.list(ORG, 0, 20)).thenReturn(List.of());
        mvc.perform(get("/api/projects")
                        .with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(projects).list(ORG, 0, 20);
    }

    @Test
    void authenticatesWithSharedPasswordEncoderAndScopesList() throws Exception {
        when(useCases.list(ORG, 0, 20)).thenReturn(List.of(result()));
        mvc.perform(get("/api/servers")
                        .with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].organizationId").value(ORG.toString()));
        verify(useCases).list(ORG, 0, 20);
    }

    @Test
    void validatesRequestBeforeCallingUseCase() throws Exception {
        mvc.perform(post("/api/servers")
                        .with(httpBasic("developer", "test-only-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists());
        verifyNoInteractions(useCases);
    }

    @Test
    void registersAndOmitsCredentialReferenceFromResponse() throws Exception {
        when(useCases.register(eq(ORG), any())).thenReturn(result());
        String body = """
                {
                  "name": "Development",
                  "host": "server.example.com",
                  "sshPort": 22,
                  "sshUsername": "deploy",
                  "credentialSecretRef": "server-credentials/development",
                  "sshHostKeyFingerprint": "%s"
                }
                """.formatted(FINGERPRINT);
        mvc.perform(post("/api/servers")
                        .with(httpBasic("developer", "test-only-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/servers/" + SERVER))
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.credentialSecretRef").doesNotExist());
    }

    @Test
    void returnsNotFoundFromApplicationFailure() throws Exception {
        when(useCases.get(ORG, SERVER)).thenThrow(new ServerException.NotFound(SERVER));
        mvc.perform(get("/api/servers/" + SERVER)
                        .with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isNotFound());
    }

    private ServerResult result() {
        return new ServerResult(SERVER, ORG, "Development", "server.example.com",
                22, "deploy", FINGERPRINT, "OFFLINE",
                Instant.parse("2026-10-18T00:00:00Z"));
    }

    @Test
    void connectionCheckRequiresSeparatePermission() throws Exception {
        mvc.perform(post("/api/servers/" + SERVER + "/connection-checks")
                        .with(user("inventory-only").authorities(
                                new SimpleGrantedAuthority("ROLE_SERVER_ACCESS"),
                                new SimpleGrantedAuthority("ORG_" + ORG))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(ServerConnectionUseCases.class));
    }

    @Test
    void connectionCheckScopesOrganizationAndReturnsResult() throws Exception {
        var checks = context.getBean(ServerConnectionUseCases.class);
        when(checks.check(ORG, SERVER)).thenReturn(new ServerConnectionResult(
                SERVER, "ACTIVE", "CONNECTED", "SSH authentication succeeded.",
                Instant.parse("2026-10-18T00:00:00Z")));
        mvc.perform(post("/api/servers/" + SERVER + "/connection-checks")
                        .with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CONNECTED"));
        verify(checks).check(ORG, SERVER);
    }

    @Test
    void busyConnectionCheckReturns429() throws Exception {
        var checks = context.getBean(ServerConnectionUseCases.class);
        when(checks.check(ORG, SERVER)).thenThrow(new ServerException.CheckBusy());
        mvc.perform(post("/api/servers/" + SERVER + "/connection-checks")
                        .with(httpBasic("developer", "test-only-password")))
                .andExpect(status().isTooManyRequests());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {
        @Bean
        ServerConnectionUseCases serverConnectionUseCases() {
            return mock(ServerConnectionUseCases.class);
        }

        @Bean
        ProjectUseCases projectUseCases() {
            return mock(ProjectUseCases.class);
        }

        @Bean
        ServerUseCases serverUseCases() {
            return mock(ServerUseCases.class);
        }
    }
}
