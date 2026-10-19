package com.shipora.application.adapter.out.http;

import com.shipora.application.adapter.in.security.CurrentOrganization;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.security.access.AccessDeniedException;
import java.util.UUID;

@Component
public final class CallerAuthorization {
    private final ObjectProvider<HttpServletRequest> requests;
    private final CurrentOrganization organizations;
    public CallerAuthorization(ObjectProvider<HttpServletRequest> requests,CurrentOrganization organizations){this.requests=requests;this.organizations=organizations;}
    public String forOrganization(UUID org){
        if(!org.equals(organizations.require(SecurityContextHolder.getContext().getAuthentication())))
            throw new AccessDeniedException("Organization identity mismatch.");
        var request=requests.getIfAvailable();String header=request==null?null:request.getHeader("Authorization");
        if(header==null || header.length()>8192 || !(header.regionMatches(true,0,"Basic ",0,6) || header.regionMatches(true,0,"Bearer ",0,7)))
            throw new AccessDeniedException("An authenticated Authorization header is required for catalog validation.");
        return header;
    }
}
