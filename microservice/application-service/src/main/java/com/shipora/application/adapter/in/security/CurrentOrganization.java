package com.shipora.application.adapter.in.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public final class CurrentOrganization {
    public UUID require(Authentication auth){
        if(auth==null || !auth.isAuthenticated())throw new AccessDeniedException("Authentication is required.");
        var identities=auth.getAuthorities().stream().map(a->a.getAuthority()).filter(a->a.startsWith("ORG_")).toList();
        if(identities.size()!=1)throw new AccessDeniedException("Exactly one organization identity is required.");
        try{return UUID.fromString(identities.getFirst().substring(4));}
        catch(IllegalArgumentException e){throw new AccessDeniedException("Invalid organization identity.");}
    }
}
