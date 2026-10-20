package com.shipora.deployment.configuration;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import java.util.*;

@Configuration
public class SecurityConfiguration {
    @Bean @Profile({"local","selfhost"}) PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder();}
    @Bean @Profile({"local","selfhost"}) UserDetailsService localUsers(@Value("${shipora.local.username}") String username,
            @Value("${shipora.local.password}") String password,@Value("${shipora.local.worker-username}") String worker,
            @Value("${shipora.local.worker-password}") String workerPassword,@Value("${shipora.local.organization-id}") UUID org,PasswordEncoder encoder,org.springframework.core.env.Environment environment){
        if(password.isBlank())throw new IllegalArgumentException("LOCAL_API_PASSWORD is required.");
        if(username.equals(worker))throw new IllegalArgumentException("Worker and API usernames must be different.");
        var users=new ArrayList<UserDetails>();
        users.add(User.withUsername(username).password(encoder.encode(password)).authorities("ROLE_DEPLOYMENT_ACCESS","ROLE_DEPLOYMENT_MANAGE","ORG_"+org).build());
        if(!workerPassword.isBlank())users.add(User.withUsername(worker).password(encoder.encode(workerPassword)).authorities("ROLE_DEPLOYMENT_WORKER","ROLE_PIPELINE_WORKER","ORG_"+org).build());
        return com.shipora.pipeline.PipelineAccounts.users(users,environment,encoder,org);
    }
    @Bean @Profile({"local","selfhost"}) SecurityFilterChain localSecurity(HttpSecurity http,org.springframework.core.env.Environment environment)throws Exception{
        if(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("selfhost")))http.addFilterAfter(new com.shipora.pipeline.PipelineRequestGuard(),org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class);
        return http.csrf(csrf->csrf.disable()).sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/deployments","/api/deployments/*","/api/deployments/*/events","/api/deployments/*/queue-status","/api/applications/*/releases").hasRole("DEPLOYMENT_ACCESS")
                        .requestMatchers(HttpMethod.POST,"/api/deployments","/api/deployments/*/retries","/api/deployments/*/cancellations").hasRole("DEPLOYMENT_MANAGE")
                        .requestMatchers(HttpMethod.POST,"/internal/deployment-jobs/claims","/internal/deployment-jobs/*/heartbeats","/internal/deployment-jobs/*/authorizations","/internal/deployment-jobs/*/events").hasRole("DEPLOYMENT_WORKER")
                        .requestMatchers(HttpMethod.POST,"/internal/git-pushes").hasRole("PIPELINE_AUTOMATION")
                        .requestMatchers(HttpMethod.PUT,"/internal/deployment-jobs/*/status").hasRole("DEPLOYMENT_WORKER")
                        .anyRequest().denyAll()).httpBasic(Customizer.withDefaults())
                .headers(headers->headers.contentSecurityPolicy(csp->csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))).build();
    }
    @Bean @Profile("!local & !selfhost") SecurityFilterChain defaultSecurity(HttpSecurity http)throws Exception{return http.authorizeHttpRequests(auth->auth.anyRequest().denyAll()).build();}
}
