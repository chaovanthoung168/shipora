package com.shipora.deployment.domain;

import java.util.*;
import static com.shipora.deployment.domain.DeploymentException.Kind.*;

public final class DeploymentRules {
    private DeploymentRules(){}
    public static UUID id(UUID id){if(id==null)throw invalid("An ID is required.");return id;}
    public static String actor(String value){return text(value,150,"actor");}
    public static String text(String value,int max,String field){
        if(value==null || value.isBlank() || value.length()>max || value.chars().anyMatch(Character::isISOControl))throw invalid("Invalid "+field+".");return value;
    }
    public static String key(String value){
        if(value==null || !value.matches("[A-Za-z0-9._:-]{1,128}"))throw invalid("Idempotency-Key is required (1-128 letters, digits, dot, underscore, colon or hyphen).");return value;
    }
    public static String commit(String value){
        if(value==null || !value.matches("(?i)([a-f0-9]{40}|[a-f0-9]{64})") || value.matches("0+"))throw invalid("commitSha must be a full non-zero Git commit hash.");return value.toLowerCase(Locale.ROOT);
    }
    public static int offset(int page,int size){
        if(page<0 || page>100000 || size<1 || size>100)throw invalid("Invalid pagination (page 0-100000, size 1-100).");return page*size;
    }
    public static void eventPage(long after,int limit){if(after<0 || limit<1 || limit>100)throw invalid("Invalid event cursor or limit.");}
    public static void version(long value){if(value<0)throw invalid("version must be non-negative.");}
    public static void transition(DeploymentStatus current,DeploymentStatus next,String image,FailureCode failure){
        if(next==null || current.terminal() || current==DeploymentStatus.QUEUED)throw conflict("Deployment cannot transition from its current status.");
        if(next==DeploymentStatus.FAILED){
            if(failure==null || failure==FailureCode.LEASE_EXPIRED || failure==FailureCode.ATTEMPTS_EXHAUSTED)throw invalid("A worker failure code is required.");
        }else{
            if(failure!=null)throw invalid("failureCode is only valid for FAILED.");
            DeploymentStatus expected=switch(current){case PREPARING->DeploymentStatus.BUILDING;case BUILDING->DeploymentStatus.PUSHING;
                case PUSHING->DeploymentStatus.DEPLOYING;case DEPLOYING->DeploymentStatus.VERIFYING;case VERIFYING->DeploymentStatus.SUCCEEDED;default->null;};
            if(next!=expected)throw conflict("Deployment stages must advance in order.");
        }
        if(next==DeploymentStatus.DEPLOYING)image(image);
        else if(image!=null)throw invalid("imageReference is only accepted when entering DEPLOYING.");
    }
    public static String image(String value){
        if(value==null || value.length()>1024 || !value.matches("[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]{1,5})?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)+@sha256:[a-f0-9]{64}"))
            throw invalid("imageReference must contain a repository and immutable sha256 digest.");return value;
    }
    public static DeploymentException invalid(String value){return new DeploymentException(INVALID,value);}
    public static DeploymentException conflict(String value){return new DeploymentException(CONFLICT,value);}
}
