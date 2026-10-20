package com.shipora.application.configuration;

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
import java.util.UUID;

@Configuration
public class SecurityConfiguration {
    @Bean @Profile({"local","selfhost"}) PasswordEncoder encoder(){return new BCryptPasswordEncoder();}
    @Bean @Profile({"local","selfhost"}) UserDetailsService localUsers(@Value("${shipora.local.username}") String username,
            @Value("${shipora.local.password}") String password,@Value("${shipora.local.organization-id}") UUID org,PasswordEncoder encoder,org.springframework.core.env.Environment environment){
        if(password.isBlank())throw new IllegalArgumentException("LOCAL_API_PASSWORD is required.");
        return com.shipora.pipeline.PipelineAccounts.users(java.util.List.of(User.withUsername(username).password(encoder.encode(password))
                .authorities("ROLE_APPLICATION_ACCESS","ROLE_APPLICATION_MANAGE","ORG_"+org).build()),environment,encoder,org,"ROLE_APPLICATION_ACCESS");
    }
    @Bean @Profile({"local","selfhost"}) SecurityFilterChain localSecurity(HttpSecurity http,org.springframework.core.env.Environment environment)throws Exception{
        if(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("selfhost")))http.addFilterAfter(new com.shipora.pipeline.PipelineRequestGuard(),org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class);
        return http.csrf(csrf->csrf.disable()).sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST,"/internal/worker/jobs/*/application-context").hasRole("PIPELINE_WORKER")
                        .requestMatchers(HttpMethod.GET,"/internal/automation/applications").hasRole("PIPELINE_AUTOMATION")
                        .requestMatchers(HttpMethod.GET,"/api/applications/*/environment","/api/applications/*/secrets").hasRole("APPLICATION_MANAGE")
                        .requestMatchers(HttpMethod.GET,"/api/applications","/api/applications/*","/api/kubernetes-targets","/api/kubernetes-targets/*").hasRole("APPLICATION_ACCESS")
                        .requestMatchers(HttpMethod.POST,"/api/kubernetes-targets","/api/kubernetes-targets/*/connection-checks").hasRole("APPLICATION_MANAGE")
                        .requestMatchers(HttpMethod.PATCH,"/api/kubernetes-targets/*").hasRole("APPLICATION_MANAGE")
                        .requestMatchers(HttpMethod.DELETE,"/api/kubernetes-targets/*").hasRole("APPLICATION_MANAGE")
                        .requestMatchers(HttpMethod.POST,"/api/applications").hasRole("APPLICATION_MANAGE")
                        .requestMatchers(HttpMethod.PATCH,"/api/applications/*").hasRole("APPLICATION_MANAGE")
                        .requestMatchers(HttpMethod.PUT,"/api/applications/*/environment","/api/applications/*/secrets/*").hasRole("APPLICATION_MANAGE")
                        .requestMatchers(HttpMethod.DELETE,"/api/applications/*","/api/applications/*/secrets/*").hasRole("APPLICATION_MANAGE")
                        .anyRequest().denyAll()).httpBasic(Customizer.withDefaults())
                .headers(headers->headers.contentSecurityPolicy(csp->csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))).build();
    }
    @Bean @Profile("!local & !selfhost") SecurityFilterChain defaultSecurity(HttpSecurity http)throws Exception{
        return http.authorizeHttpRequests(auth->auth.anyRequest().denyAll()).build();
    }
}
