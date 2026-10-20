package com.shipora.application.adapter.in.web;

import com.shipora.application.adapter.in.security.CurrentOrganization;
import com.shipora.application.application.port.in.KubernetesTargetUseCases;
import com.shipora.application.application.port.in.KubernetesTargetUseCases.Result;
import com.shipora.application.configuration.SecurityConfiguration;
import com.shipora.application.domain.*;
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

class KubernetesTargetWebSecurityTest {
    static final UUID ORG=UUID.fromString("11111111-1111-1111-1111-111111111111");
    final UUID id=UUID.randomUUID();AnnotationConfigWebApplicationContext context;KubernetesTargetUseCases service;MockMvc mvc;
    @BeforeEach void setup(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.getEnvironment().setActiveProfiles("local");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"shipora.local.username=developer","shipora.local.password=test-only","shipora.local.organization-id="+ORG);
        context.register(TestConfig.class,SecurityConfiguration.class,CurrentOrganization.class,KubernetesTargetController.class,ApplicationExceptionHandler.class);
        context.refresh();service=context.getBean(KubernetesTargetUseCases.class);mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close(){context.close();}
    @Test void unauthenticatedRequestsCannotListOrCheckTargets()throws Exception {
        mvc.perform(get("/api/kubernetes-targets")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/kubernetes-targets/"+id+"/connection-checks").contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void readOnlyUsersCannotRegisterRotateDeleteOrCheckCredentials()throws Exception {
        var reader=user("reader").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_APPLICATION_ACCESS"),new org.springframework.security.core.authority.SimpleGrantedAuthority("ORG_"+ORG));
        mvc.perform(post("/api/kubernetes-targets").with(reader).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        mvc.perform(post("/api/kubernetes-targets/"+id+"/connection-checks").with(reader).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/kubernetes-targets/"+id).with(reader).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"token\":\"new-token\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/kubernetes-targets/"+id).with(reader).param("version","0")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void missingTenantCannotAccessTargets()throws Exception {
        mvc.perform(get("/api/kubernetes-targets").with(user("reader").roles("APPLICATION_ACCESS"))).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void registrationReturnsOnlyMetadataAndUsesTheAuthenticatedTenant()throws Exception {
        when(service.create(eq(ORG),any())).thenReturn(result());
        mvc.perform(post("/api/kubernetes-targets").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated()).andExpect(header().string("Location","/api/kubernetes-targets/"+id)).andExpect(header().string("ETag","\"0\""))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("status").value("UNVERIFIED"))
                .andExpect(jsonPath("token").doesNotExist()).andExpect(jsonPath("credentials").doesNotExist()).andExpect(jsonPath("certificateAuthorityData").doesNotExist());
        verify(service).create(eq(ORG),argThat(in->in.token().equals("private-token")));
        org.junit.jupiter.api.Assertions.assertFalse(new KubernetesTargetController.CreateRequest("Cluster","https://cluster","shipora","private-token",null).toString().contains("private-token"));
    }
    @Test void checksAndRotationsRequireVersionAndMalformedBodiesDoNotEchoTokens()throws Exception {
        mvc.perform(post("/api/kubernetes-targets/"+id+"/connection-checks").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/kubernetes-targets/"+id).with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"private-token\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("detail").value("Invalid request body or parameters."));verifyNoInteractions(service);
    }
    @Test void failedProbeReturnsRecordedStatusAndStaleVersionReturns409()throws Exception {
        when(service.check(ORG,id,0)).thenReturn(new Result(id,ORG,"Cluster","https://cluster","shipora",KubernetesTarget.Status.ERROR,"ACCESS_DENIED",1,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH));
        mvc.perform(post("/api/kubernetes-targets/"+id+"/connection-checks").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("ERROR")).andExpect(jsonPath("checkCode").value("ACCESS_DENIED")).andExpect(jsonPath("version").value(1));
        when(service.check(ORG,id,0)).thenThrow(new ApplicationException(ApplicationException.Kind.CONFLICT,"Version changed"));
        mvc.perform(post("/api/kubernetes-targets/"+id+"/connection-checks").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());
    }
    String body(){return "{\"name\":\"Cluster\",\"apiServer\":\"https://cluster\",\"namespace\":\"shipora\",\"token\":\"private-token\"}";}
    Result result(){return new Result(id,ORG,"Cluster","https://cluster","shipora",KubernetesTarget.Status.UNVERIFIED,null,0,Instant.EPOCH,Instant.EPOCH,null);}
    @Configuration @EnableWebMvc @EnableWebSecurity static class TestConfig {@Bean KubernetesTargetUseCases targets(){return mock(KubernetesTargetUseCases.class);}}
}
