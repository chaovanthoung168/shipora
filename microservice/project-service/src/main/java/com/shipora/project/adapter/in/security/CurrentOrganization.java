package com.shipora.project.adapter.in.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CurrentOrganization {

    public UUID require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException(
                    "Authentication is required."
            );
        }

        String organizationId = authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ORG_"))
                .map(authority -> authority.substring("ORG_".length()))
                .findFirst()
                .orElseThrow(
                        () -> new AccessDeniedException(
                                "Organization access is required."
                        )
                );

        try {
            return UUID.fromString(organizationId);
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException(
                    "Invalid organization identity."
            );
        }
    }
}
