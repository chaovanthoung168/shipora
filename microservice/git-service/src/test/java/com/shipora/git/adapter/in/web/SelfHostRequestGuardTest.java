package com.shipora.git.adapter.in.web;

import com.shipora.git.adapter.in.security.SelfHostRequestGuard;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class SelfHostRequestGuardTest {
    @Test void browserFormCannotStartSetupWithoutExplicitApiHeader() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/git/setup/github");
        var response=new MockHttpServletResponse(); var chain=new MockFilterChain();
        new SelfHostRequestGuard().doFilter(request,response,chain);
        assertEquals(403,response.getStatus()); assertNull(chain.getRequest());
    }
    @Test void apiClientCanStartSetupWithExplicitHeader() throws Exception {
        var request=new MockHttpServletRequest("POST","/api/git/setup/github"); request.addHeader("X-Shipora-Request","true");
        var response=new MockHttpServletResponse(); var chain=new MockFilterChain();
        new SelfHostRequestGuard().doFilter(request,response,chain); assertNotNull(chain.getRequest());
    }
    @Test void githubWebhooksAndGetCallbacksDoNotRequireTheApiHeader() throws Exception {
        for(var request:new MockHttpServletRequest[]{new MockHttpServletRequest("POST","/api/git/webhooks/github"),
                new MockHttpServletRequest("GET","/api/git/setup/github/callback")}) {
            var chain=new MockFilterChain(); new SelfHostRequestGuard().doFilter(request,new MockHttpServletResponse(),chain);
            assertNotNull(chain.getRequest());
        }
    }
}
