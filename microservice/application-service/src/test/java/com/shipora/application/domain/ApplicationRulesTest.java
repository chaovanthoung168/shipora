package com.shipora.application.domain;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationRulesTest {
    @Test void rejectsTraversalAbsoluteAndPlatformSpecificBuildPaths(){
        for(String value:List.of("../app","/etc/passwd","C:/app","apps/../app","apps\\web","apps//web","./Dockerfile"))
            assertThrows(ApplicationException.class,()->ApplicationRules.path(value,false));
        assertEquals(".",ApplicationRules.path(".",true));assertEquals("apps/web/Dockerfile",ApplicationRules.path("apps/web/Dockerfile",false));
    }
    @Test void branchAndHealthPathRejectUnsafeOrAmbiguousInputs(){
        for(String value:List.of("-main","feat..x","a//b","a.lock","a/hidden.lock","main;whoami","main\n"))
            assertThrows(ApplicationException.class,()->ApplicationRules.branch(value));
        for(String value:List.of("https://example.com","//example.com","/../private","/health?q=x"))
            assertThrows(ApplicationException.class,()->ApplicationRules.health(value));
        assertEquals("feature/next-1",ApplicationRules.branch("feature/next-1"));
    }
    @Test void environmentConfigurationHasExplicitBoundsAndPreservesValues(){
        assertThrows(ApplicationException.class,()->ApplicationRules.environment(Map.of("BAD-KEY","x")));
        assertThrows(ApplicationException.class,()->ApplicationRules.environment(Map.of("TOKEN","a\0b")));
        assertThrows(ApplicationException.class,()->ApplicationRules.environment(Map.of("VALUE","a".repeat(16385))));
        var tooLarge=new HashMap<String,String>();for(int i=0;i<17;i++)tooLarge.put("KEY_"+i,"a".repeat(16384));
        assertThrows(ApplicationException.class,()->ApplicationRules.environment(tooLarge));
        assertEquals("",ApplicationRules.environment(Map.of("EMPTY","")).get("EMPTY"));
    }
    @Test void validatesSlugPortAndPagination(){
        for(String slug:List.of("Hello","a--b","-a","a/../b"))assertThrows(ApplicationException.class,()->ApplicationRules.slug(slug));
        assertThrows(ApplicationException.class,()->ApplicationRules.port(0));
        assertThrows(ApplicationException.class,()->ApplicationRules.offset(Integer.MAX_VALUE,100));
        assertThrows(ApplicationException.class,()->ApplicationRules.offset(0,101));
        assertEquals(40,ApplicationRules.offset(2,20));
    }
}
