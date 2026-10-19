package com.shipora.application.adapter.out.http;

import com.shipora.application.adapter.in.security.CurrentOrganization;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CallerAuthorizationTest {
    final UUID org=UUID.randomUUID();
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test @SuppressWarnings("unchecked") void forwardsOnlyAuthenticatedSameOrganizationCredentials(){
        var requests=(ObjectProvider<HttpServletRequest>)mock(ObjectProvider.class);
        var request=new MockHttpServletRequest();request.addHeader("Authorization","basic test-only");
        when(requests.getIfAvailable()).thenReturn(request);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user","erased",
                List.of(new SimpleGrantedAuthority("ORG_"+org))));
        var caller=new CallerAuthorization(requests,new CurrentOrganization());
        assertEquals("basic test-only",caller.forOrganization(org));
        assertThrows(AccessDeniedException.class,()->caller.forOrganization(UUID.randomUUID()));
    }
    @Test @SuppressWarnings("unchecked") void missingAuthenticationOrAuthorizationHeaderCannotBorrowAnIdentity(){
        var requests=(ObjectProvider<HttpServletRequest>)mock(ObjectProvider.class);
        var caller=new CallerAuthorization(requests,new CurrentOrganization());
        assertThrows(AccessDeniedException.class,()->caller.forOrganization(org));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("user","erased",
                List.of(new SimpleGrantedAuthority("ORG_"+org))));
        when(requests.getIfAvailable()).thenReturn(new MockHttpServletRequest());
        assertThrows(AccessDeniedException.class,()->caller.forOrganization(org));
    }
}
