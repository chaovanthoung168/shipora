package com.shipora.git.configuration;

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
    @Bean @Profile({"local", "selfhost"}) PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean @Profile({"local", "selfhost"}) UserDetailsService localUsers(
            @Value("${shipora.local.username}") String username, @Value("${shipora.local.password}") String password,
            @Value("${shipora.local.organization-id}") UUID organizationId, PasswordEncoder encoder,org.springframework.core.env.Environment environment) {
        if (password.isBlank()) throw new IllegalArgumentException("LOCAL_API_PASSWORD is required.");
        return com.shipora.pipeline.PipelineAccounts.users(java.util.List.of(User.withUsername(username).password(encoder.encode(password))
                .authorities("ROLE_GIT_ACCESS", "ROLE_GIT_MANAGE", "ROLE_GIT_ADMIN", "ORG_" + organizationId).build()),environment,encoder,organizationId,"ROLE_GIT_ACCESS");
    }
    @Bean @Profile({"local", "selfhost"}) SecurityFilterChain localSecurity(HttpSecurity http, org.springframework.core.env.Environment environment) throws Exception {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("selfhost"))) {
            http.addFilterAfter(new com.shipora.git.adapter.in.security.SelfHostRequestGuard(),
                    org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class);
        }
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST,"/internal/worker/jobs/*/source-context").hasRole("PIPELINE_WORKER")
                        .requestMatchers(HttpMethod.GET, "/api/git/setup/github/callback").hasRole("GIT_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/git/setup/github", "/api/git/setup/github/callback").hasRole("GIT_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/git/setup/github").hasRole("GIT_ACCESS")
                        .requestMatchers(request -> "GET".equals(request.getMethod())
                                && (request.getContextPath() + "/api/git/github/callback").equals(request.getRequestURI())
                                && request.getParameter("state") != null && request.getParameter("state").length() == 45
                                && request.getParameter("state").startsWith("c_"))
                        .hasRole("GIT_MANAGE")
                        .requestMatchers(HttpMethod.GET, "/api/git/github/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/git/webhooks/github").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/git/connections", "/api/git/connections/accounts",
                                "/api/git/github/callback", "/api/git/installations/*/sync").hasRole("GIT_MANAGE")
                        .requestMatchers(HttpMethod.GET, "/api/git/connections/accounts", "/api/git/installations",
                                "/api/git/installations/*/repositories", "/api/git/repositories/*",
                                "/api/git/repositories/*/commits").hasRole("GIT_ACCESS")
                        .anyRequest().denyAll())
                .headers(headers -> headers.referrerPolicy(referrer -> referrer.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .httpBasic(Customizer.withDefaults()).build();
    }
    @Bean @Profile("!local & !selfhost") SecurityFilterChain defaultSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll()).build();
    }
}
