package com.shipora.deployment.adapter.in.web;

import com.shipora.deployment.adapter.in.security.CurrentOrganization;
import com.shipora.deployment.application.dto.*;
import com.shipora.deployment.application.port.in.DeploymentUseCases;
import com.shipora.deployment.configuration.SecurityConfiguration;
import com.shipora.deployment.domain.*;
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

class DeploymentWebSecurityTest {
    static final UUID ORG=UUID.fromString("11111111-1111-1111-1111-111111111111");
    final UUID id=UUID.randomUUID(),app=UUID.randomUUID();AnnotationConfigWebApplicationContext context;DeploymentUseCases service;MockMvc mvc;
    @BeforeEach void setup(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.getEnvironment().setActiveProfiles("local");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"shipora.local.username=developer","shipora.local.password=test-only","shipora.local.worker-username=worker",
                "shipora.local.worker-password=worker-test-only","shipora.local.organization-id="+ORG);
        context.register(TestConfig.class,SecurityConfiguration.class,CurrentOrganization.class,DeploymentController.class,WorkerController.class,DeploymentExceptionHandler.class);
        context.refresh();service=context.getBean(DeploymentUseCases.class);mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close(){context.close();}
    DeploymentResult result(){return DeploymentResult.from(Deployment.builder().id(id).organizationId(ORG).applicationId(app).status(DeploymentStatus.QUEUED).commitSha("a".repeat(40)).createdAt(Instant.EPOCH).build());}
    String createBody(){return "{\"applicationId\":\""+app+"\",\"commitSha\":\""+"a".repeat(40)+"\"}";}
    @Test void missingOrIncorrectAuthenticationIs401()throws Exception{
        mvc.perform(get("/api/deployments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/deployments").with(httpBasic("developer","wrong"))).andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void apiUsersCannotClaimJobsAndWorkersCannotCreateDeployments()throws Exception{
        mvc.perform(post("/internal/deployment-jobs/claims").with(httpBasic("developer","test-only"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/deployments").with(httpBasic("worker","worker-test-only")).header("Idempotency-Key","test-1").contentType(MediaType.APPLICATION_JSON).content(createBody())).andExpect(status().isForbidden());
        mvc.perform(get("/api/deployments").with(httpBasic("worker","worker-test-only"))).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void readOnlyOrMissingTenantCannotMutateOrReadUnscopedData()throws Exception{
        mvc.perform(post("/api/deployments").with(user("reader").roles("DEPLOYMENT_ACCESS")).header("Idempotency-Key","test-1").contentType(MediaType.APPLICATION_JSON).content(createBody())).andExpect(status().isForbidden());
        mvc.perform(get("/api/deployments").with(user("reader").roles("DEPLOYMENT_ACCESS"))).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void createIsAcceptedWithLocationAndUsesTrustedIdentity()throws Exception{
        when(service.create(eq(ORG),eq("developer"),eq("test-1"),any())).thenReturn(result());
        mvc.perform(post("/api/deployments").with(httpBasic("developer","test-only")).header("Idempotency-Key","test-1").contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isAccepted()).andExpect(header().string("Location","/api/deployments/"+id)).andExpect(header().string("ETag","\"0\""))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("Set-Cookie")).andExpect(jsonPath("organizationId").value(ORG.toString()))
                .andExpect(jsonPath("leaseToken").doesNotExist());
    }
    @Test void missingKeyVersionAndMalformedWorkerTokensAre400()throws Exception{
        mvc.perform(post("/api/deployments").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content(createBody())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/deployments/"+id+"/cancellations").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/deployment-jobs/"+id+"/heartbeats").with(httpBasic("worker","worker-test-only")).contentType(MediaType.APPLICATION_JSON).content("{\"leaseToken\":\"private-invalid-token\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("detail").value("Invalid request body or parameters."));verifyNoInteractions(service);
    }
    @Test void workerIdentityIsDerivedFromAuthenticationAndEmptyQueueIs204()throws Exception{
        when(service.claim(ORG,"worker")).thenReturn(Optional.empty());
        mvc.perform(post("/internal/deployment-jobs/claims").with(httpBasic("worker","worker-test-only"))).andExpect(status().isNoContent());verify(service).claim(ORG,"worker");
    }
    @Test void processEventsRequireWorkerRoleAndLeaseAndQueueDiagnosticsRequireApiIdentity()throws Exception{
        UUID token=UUID.randomUUID();String body="{\"leaseToken\":\""+token+"\",\"events\":[{\"level\":\"INFO\",\"message\":\"npm run build\"}]}";
        mvc.perform(post("/internal/deployment-jobs/"+id+"/events").with(httpBasic("developer","test-only")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/internal/deployment-jobs/"+id+"/events").with(httpBasic("worker","worker-test-only")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        verify(service).appendEvents(eq(ORG),eq("worker"),eq(id),eq(token),any());
        when(service.queueStatus(ORG,id)).thenReturn(new QueueStatus("NO_WORKER",null,0,Instant.EPOCH));
        mvc.perform(get("/api/deployments/"+id+"/queue-status").with(httpBasic("developer","test-only"))).andExpect(status().isOk()).andExpect(jsonPath("reason").value("NO_WORKER"));
        mvc.perform(get("/api/deployments/"+id+"/queue-status").with(httpBasic("worker","worker-test-only"))).andExpect(status().isForbidden());
    }
    @Test void claimReturnsOpaqueLeaseOnlyToAuthorizedWorker()throws Exception{
        UUID token=UUID.randomUUID();when(service.claim(ORG,"worker")).thenReturn(Optional.of(new JobClaim(id,token,Instant.now().plusSeconds(60),1,result())));
        mvc.perform(post("/internal/deployment-jobs/claims").with(httpBasic("worker","worker-test-only"))).andExpect(status().isOk())
                .andExpect(jsonPath("leaseToken").value(token.toString())).andExpect(header().string("Cache-Control","no-store"));
        org.junit.jupiter.api.Assertions.assertFalse(new WorkerController.HeartbeatRequest(token).toString().contains(token.toString()));
    }
    @Test void scopedMissingAndStaleLeaseErrorsUse404And409()throws Exception{
        when(service.get(ORG,id)).thenThrow(new DeploymentException(DeploymentException.Kind.NOT_FOUND,"Deployment not found."));
        mvc.perform(get("/api/deployments/"+id).with(httpBasic("developer","test-only"))).andExpect(status().isNotFound());
        UUID token=UUID.randomUUID();when(service.heartbeat(ORG,"worker",id,token)).thenThrow(new DeploymentException(DeploymentException.Kind.CONFLICT,"Job lease is no longer valid."));
        mvc.perform(post("/internal/deployment-jobs/"+id+"/heartbeats").with(httpBasic("worker","worker-test-only")).contentType(MediaType.APPLICATION_JSON).content("{\"leaseToken\":\""+token+"\"}"))
                .andExpect(status().isConflict());
    }
    @Test void storageDetailsAreSanitized()throws Exception{
        when(service.get(ORG,id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("password=private-detail"));
        mvc.perform(get("/api/deployments/"+id).with(httpBasic("developer","test-only"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("detail").value("Deployment storage is unavailable. Retry with the same Idempotency-Key."));
    }
    @Test void nonLocalProfileDeniesAccessEvenForAnAuthenticatedManager()throws Exception{
        try(var production=new AnnotationConfigWebApplicationContext()){
            production.setServletContext(new MockServletContext());production.getEnvironment().setActiveProfiles("production");
            production.register(TestConfig.class,SecurityConfiguration.class,CurrentOrganization.class,DeploymentController.class,WorkerController.class,DeploymentExceptionHandler.class);production.refresh();
            var client=MockMvcBuilders.webAppContextSetup(production).apply(springSecurity()).build();
            client.perform(get("/api/deployments").with(user("manager").roles("DEPLOYMENT_ACCESS","DEPLOYMENT_MANAGE"))).andExpect(status().isForbidden());
            verifyNoInteractions(production.getBean(DeploymentUseCases.class));
        }
    }
    @Configuration @EnableWebMvc @EnableWebSecurity static class TestConfig{@Bean DeploymentUseCases service(){return mock(DeploymentUseCases.class);}}
}
