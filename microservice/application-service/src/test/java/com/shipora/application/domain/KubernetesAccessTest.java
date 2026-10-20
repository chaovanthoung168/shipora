package com.shipora.application.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KubernetesAccessTest {
    @Test void validatesEndpointAndNormalizesOrigin() {
        assertEquals("https://cluster.example:6443",access("https://cluster.example:6443/","shipora","token",null).apiServer());
        for(String endpoint:new String[]{"http://localhost:6443","https://user:pass@cluster","https://cluster/path","https://cluster?token=x","https://cluster/#fragment","https://cluster:0",null})
            assertThrows(ApplicationException.class,()->access(endpoint,"shipora","token",null));
    }
    @Test void rejectsHeaderInjectionInvalidNamespacesAndInvalidCertificateEncoding() {
        for(String namespace:new String[]{"Default","-bad","bad-","a.b","a/b","x".repeat(64),null})
            assertThrows(ApplicationException.class,()->access("https://cluster",namespace,"token",null));
        for(String token:new String[]{"","token\r\nHeader: x","has space",null})
            assertThrows(ApplicationException.class,()->access("https://cluster","shipora",token,null));
        assertThrows(ApplicationException.class,()->access("https://cluster","shipora","token","not base64"));
        assertEquals("",access("https://cluster","shipora","token",null).certificateAuthorityData());
        assertFalse(access("https://cluster","shipora","private-token",null).toString().contains("private-token"));
    }
    private KubernetesAccess access(String uri,String ns,String token,String ca){return new KubernetesAccess(uri,ns,token,ca);}
}
