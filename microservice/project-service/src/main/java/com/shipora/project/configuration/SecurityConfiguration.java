package com.shipora.project.configuration;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.UUID;

@Configuration
public class SecurityConfiguration {

    @Bean
    @Profile("local")
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Profile("local")
    UserDetailsService localUsers(
            @Value("${shipora.local.username}") String username,
            @Value("${shipora.local.password}") String password,
            @Value("${shipora.local.organization-id}") UUID organizationId,
            PasswordEncoder passwordEncoder
    ) {
        var developer = User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .authorities(
                        "ROLE_PROJECT_ACCESS",
                        "ROLE_SERVER_ACCESS",
                        "ROLE_SERVER_CONNECT",
                        "ORG_" + organizationId
                )
                .build();

        return new InMemoryUserDetailsManager(developer);
    }

    @Bean
    @Profile("local")
    SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        return http
                // Local command-line development only.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/servers/*/connection-checks")
                        .hasRole("SERVER_CONNECT")
                        .requestMatchers(
                                "/api/projects",
                                "/api/projects/**"
                        )
                        .hasRole("PROJECT_ACCESS")
                        .requestMatchers(
                                "/api/servers",
                                "/api/servers/**"
                        )
                        .hasRole("SERVER_ACCESS")
                        .anyRequest()
                        .denyAll()
                )
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    @Profile("!local")
    SecurityFilterChain defaultSecurity(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest()
                        .denyAll()
                )
                .build();
    }
}
