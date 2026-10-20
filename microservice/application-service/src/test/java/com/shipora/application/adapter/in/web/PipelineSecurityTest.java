package com.shipora.application.adapter.in.web;

import com.shipora.application.adapter.in.security.CurrentOrganization;
import com.shipora.application.application.port.out.ApplicationStore;
import com.shipora.application.application.service.WorkerContextService;
import com.shipora.application.configuration.SecurityConfiguration;
import com.shipora.pipeline.*;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PipelineSecurityTest {
    final UUID org=UUID.fromString("11111111-1111-1111-1111-111111111111"),job=UUID.randomUUID(),token=UUID.randomUUID();
    AnnotationConfigWebApplicationContext context; MockMvc mvc; LeaseAuthorizer leases; WorkerContextService secrets;
    @BeforeEach void setup() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().setActiveProfiles("selfhost");
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"shipora.local.username=admin","shipora.local.password=admin-test",
                "shipora.local.organization-id="+org,"shipora.local.worker-username=worker","shipora.local.worker-password=worker-test",
                "SHIPORA_AUTOMATION_USERNAME=automation","SHIPORA_AUTOMATION_PASSWORD=automation-test");
        context.register(Config.class,SecurityConfiguration.class,CurrentOrganization.class,PipelineController.class,PipelineHttpAdvice.class);
        context.refresh();leases=context.getBean(LeaseAuthorizer.class);secrets=context.getBean(WorkerContextService.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void close(){context.close();}
    String endpoint(){return "/internal/worker/jobs/"+job+"/application-context";}
    String body(){return "{\"leaseToken\":\""+token+"\"}";}
    @Test void onlyWorkerIdentityCanRequestJobSecrets() throws Exception {
        for(var identity:List.of(new String[]{"admin","admin-test"},new String[]{"automation","automation-test"}))
            mvc.perform(post(endpoint()).with(httpBasic(identity[0],identity[1])).header("X-Shipora-Request","true").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(leases,secrets);
    }
    @Test void workerGetsSecretsOnlyAfterSuccessfulLeaseValidationAndCannotCacheThem() throws Exception {
        var grant=new Protocol.Job(job,token,Instant.now().plusSeconds(60),1,null,1);
        when(leases.authorize(eq(org),eq(job),eq(token),anyString())).thenReturn(grant);
        when(secrets.context(grant)).thenReturn(new Protocol.ApplicationContext(2,Map.of("TOKEN","fixture-secret"),null));
        mvc.perform(post(endpoint()).with(httpBasic("worker","worker-test")).header("X-Shipora-Request","true").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("environment.TOKEN").value("fixture-secret"));
        var order=inOrder(leases,secrets);order.verify(leases).authorize(eq(org),eq(job),eq(token),anyString());order.verify(secrets).context(grant);
    }
    @Test void staleLeaseAndMissingSelfhostRequestHeaderCannotReadSecrets() throws Exception {
        mvc.perform(post(endpoint()).with(httpBasic("worker","worker-test")).contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        when(leases.authorize(eq(org),eq(job),eq(token),anyString())).thenThrow(new InternalHttp.RemoteFailure(409));
        mvc.perform(post(endpoint()).with(httpBasic("worker","worker-test")).header("X-Shipora-Request","true").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isConflict());
        verifyNoInteractions(secrets);
    }
    @Test void workerCannotReadManagementSecretsOrAutomationCatalog() throws Exception {
        mvc.perform(get("/api/applications/"+UUID.randomUUID()+"/secrets").with(httpBasic("worker","worker-test"))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/automation/applications").with(httpBasic("worker","worker-test")).param("repositoryId",UUID.randomUUID().toString()).param("branch","main")).andExpect(status().isForbidden());
        verifyNoInteractions(leases,secrets,context.getBean(ApplicationStore.class));
    }
    @Configuration @EnableWebMvc @EnableWebSecurity static class Config {
        @Bean LeaseAuthorizer leases(){return mock(LeaseAuthorizer.class);}
        @Bean WorkerContextService contexts(){return mock(WorkerContextService.class);}
        @Bean ApplicationStore applications(){return mock(ApplicationStore.class);}
    }
}
