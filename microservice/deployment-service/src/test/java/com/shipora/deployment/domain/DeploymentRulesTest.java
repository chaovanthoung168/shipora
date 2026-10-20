package com.shipora.deployment.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentRulesTest {
    @Test void requiresFullCommitHashesAndSafeIdempotencyKeys(){
        assertEquals("a".repeat(40),DeploymentRules.commit("A".repeat(40)));
        for(String value:new String[]{"abc123","0".repeat(40),"g".repeat(40),null})assertThrows(DeploymentException.class,()->DeploymentRules.commit(value));
        for(String value:new String[]{"",null,"bad\nkey","x".repeat(129),"has space"})assertThrows(DeploymentException.class,()->DeploymentRules.key(value));
        assertEquals("request:1",DeploymentRules.key("request:1"));
    }
    @Test void enforcesEveryStageInOrder(){
        var stages=new DeploymentStatus[]{DeploymentStatus.PREPARING,DeploymentStatus.BUILDING,DeploymentStatus.PUSHING,DeploymentStatus.DEPLOYING,DeploymentStatus.VERIFYING,DeploymentStatus.SUCCEEDED};
        for(int i=0;i<stages.length-1;i++)DeploymentRules.transition(stages[i],stages[i+1],stages[i+1]==DeploymentStatus.DEPLOYING?image():null,null);
        assertThrows(DeploymentException.class,()->DeploymentRules.transition(DeploymentStatus.PREPARING,DeploymentStatus.SUCCEEDED,null,null));
        assertThrows(DeploymentException.class,()->DeploymentRules.transition(DeploymentStatus.QUEUED,DeploymentStatus.BUILDING,null,null));
        assertThrows(DeploymentException.class,()->DeploymentRules.transition(DeploymentStatus.SUCCEEDED,DeploymentStatus.FAILED,null,FailureCode.WORKER_FAILED));
    }
    @Test void requiresDigestAndRejectsTagsCredentialsAndArbitraryFailureMessages(){
        assertEquals(image(),DeploymentRules.image(image()));
        for(String value:new String[]{"registry.example/web:latest","https://registry.example/web@sha256:"+"a".repeat(64),"user:pass@registry/web",null})assertThrows(DeploymentException.class,()->DeploymentRules.image(value));
        assertThrows(DeploymentException.class,()->DeploymentRules.transition(DeploymentStatus.PUSHING,DeploymentStatus.DEPLOYING,null,null));
        assertThrows(DeploymentException.class,()->DeploymentRules.transition(DeploymentStatus.BUILDING,DeploymentStatus.PUSHING,image(),null));
        assertThrows(DeploymentException.class,()->DeploymentRules.transition(DeploymentStatus.BUILDING,DeploymentStatus.FAILED,null,null));
        assertThrows(DeploymentException.class,()->DeploymentRules.transition(DeploymentStatus.BUILDING,DeploymentStatus.FAILED,null,FailureCode.LEASE_EXPIRED));
        DeploymentRules.transition(DeploymentStatus.BUILDING,DeploymentStatus.FAILED,null,FailureCode.BUILD_FAILED);
    }
    @Test void boundsPaginationVersionsAndEventCursors(){
        assertThrows(DeploymentException.class,()->DeploymentRules.offset(-1,20));assertThrows(DeploymentException.class,()->DeploymentRules.offset(0,101));
        assertThrows(DeploymentException.class,()->DeploymentRules.eventPage(-1,100));assertThrows(DeploymentException.class,()->DeploymentRules.version(-1));
        assertEquals(40,DeploymentRules.offset(2,20));
    }
    String image(){return "registry.example:5000/shipora/web@sha256:"+"a".repeat(64);}
}
