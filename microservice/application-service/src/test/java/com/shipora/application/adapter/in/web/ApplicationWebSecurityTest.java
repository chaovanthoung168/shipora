package com.shipora.application.adapter.in.web;

import com.shipora.application.adapter.in.security.CurrentOrganization;
import com.shipora.application.application.dto.*;
import com.shipora.application.application.port.in.ApplicationUseCases;
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

class ApplicationWebSecurityTest {
    static final UUID ORG=UUID.fromString("11111111-1111-1111-1111-111111111111");
    final UUID id=UUID.randomUUID();
    AnnotationConfigWebApplicationContext context;ApplicationUseCases service;MockMvc mvc;
    @BeforeEach void setup(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.getEnvironment().setActiveProfiles("local");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"shipora.local.username=developer","shipora.local.password=test-only","shipora.local.organization-id="+ORG);
        context.register(TestConfig.class,SecurityConfiguration.class,CurrentOrganization.class,ApplicationController.class,ApplicationExceptionHandler.class);
        context.refresh();service=context.getBean(ApplicationUseCases.class);mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void missingOrWrongAuthenticationReturns401()throws Exception{
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/applications").with(httpBasic("developer","wrong"))).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void readOnlyUsersCannotMutateOrReadConfiguration()throws Exception{
        var reader=user("reader").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_APPLICATION_ACCESS"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ORG_"+ORG));
        mvc.perform(post("/api/applications").with(reader).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/applications/"+id+"/environment").with(reader)).andExpect(status().isForbidden());
        mvc.perform(get("/api/applications/"+id+"/secrets").with(reader)).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void missingOrAmbiguousOrganizationIsRejected()throws Exception{
        mvc.perform(get("/api/applications").with(user("reader").roles("APPLICATION_ACCESS"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/applications").with(user("reader").authorities(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_APPLICATION_ACCESS"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ORG_"+ORG),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ORG_"+UUID.randomUUID())))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void createUsesAuthenticatedOrganizationAndReturnsLocationVersionAndNoCookie()throws Exception{
        when(service.create(eq(ORG),any())).thenReturn(result());
        mvc.perform(post("/api/applications").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated()).andExpect(header().string("Location","/api/applications/"+id))
                .andExpect(header().string("ETag","\"0\"")).andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("organizationId").value(ORG.toString())).andExpect(jsonPath("version").value(0));
        verify(service).create(eq(ORG),any());
    }
    @Test void invalidPortAndMissingPatchVersionAre400()throws Exception{
        mvc.perform(post("/api/applications").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON)
                .content(createBody().replace("\"containerPort\":3000","\"containerPort\":0"))).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/applications/"+id).with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void crossTenantMissingApplicationRemains404()throws Exception{
        when(service.get(ORG,id)).thenThrow(new ApplicationException(ApplicationException.Kind.NOT_FOUND,"Application not found."));
        mvc.perform(get("/api/applications/"+id).with(httpBasic("developer","test-only"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("detail").value("Application not found."));
    }
    @Test void secretWritesNeverEchoValuesAndAreNotCached()throws Exception{
        when(service.putSecret(ORG,id,0,"TOKEN","private-value")).thenReturn(new ApplicationUseCases.SecretKeysResult(1,List.of("TOKEN")));
        mvc.perform(put("/api/applications/"+id+"/secrets/TOKEN").with(httpBasic("developer","test-only"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"value\":\"private-value\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("keys[0]").value("TOKEN")).andExpect(jsonPath("value").doesNotExist())
                .andExpect(header().string("Cache-Control","no-store"));
        org.junit.jupiter.api.Assertions.assertFalse(new ApplicationController.SecretRequest(0L,"private-value").toString().contains("private-value"));
    }
    @Test void staleVersionMapsTo409AndDeleteRequiresVersion()throws Exception{
        doThrow(new ApplicationException(ApplicationException.Kind.CONFLICT,"Reload and retry.")).when(service).delete(ORG,id,1);
        mvc.perform(delete("/api/applications/"+id).with(httpBasic("developer","test-only")).param("version","1")).andExpect(status().isConflict());
        mvc.perform(delete("/api/applications/"+id).with(httpBasic("developer","test-only"))).andExpect(status().isBadRequest());
    }
    @Test void dependencyAndStorageFailuresReturnSanitized503()throws Exception{
        when(service.get(ORG,id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db-password=private-value"));
        mvc.perform(get("/api/applications/"+id).with(httpBasic("developer","test-only"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("detail").value("Application storage is unavailable."));
    }
    @Test void configurationAndSecretOperationsUseScopedApplicationId()throws Exception{
        when(service.replaceEnvironment(ORG,id,0,Map.of("NODE_ENV","production"))).thenReturn(new ApplicationUseCases.EnvironmentResult(1,Map.of("NODE_ENV","production")));
        mvc.perform(put("/api/applications/"+id+"/environment").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"variables\":{\"NODE_ENV\":\"production\"}}")).andExpect(status().isOk()).andExpect(jsonPath("version").value(1));
        verify(service).replaceEnvironment(ORG,id,0,Map.of("NODE_ENV","production"));
    }
    String createBody(){return "{\"projectId\":\""+UUID.randomUUID()+"\",\"repositoryId\":\""+UUID.randomUUID()+"\",\"serverId\":\""+UUID.randomUUID()+"\",\"name\":\"Web\",\"slug\":\"web\",\"containerPort\":3000}";}
    ApplicationResult result(){return new ApplicationResult(id,ORG,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"Web","web","main","Dockerfile",".",3000,"/",false,0,Instant.EPOCH,Instant.EPOCH);}
    @Configuration @EnableWebMvc @EnableWebSecurity
    static class TestConfig{@Bean ApplicationUseCases service(){return mock(ApplicationUseCases.class);}}
}
