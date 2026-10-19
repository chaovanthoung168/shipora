package com.shipora.application.adapter.out.crypto;

import com.shipora.application.application.port.out.SecretCipher;
import com.shipora.application.domain.ApplicationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FileSecretCipherTest {
    @TempDir Path directory;
    final com.shipora.application.application.port.out.SecretKeyRegistry registry=new com.shipora.application.application.port.out.SecretKeyRegistry(){
        final java.util.concurrent.ConcurrentHashMap<String,String> values=new java.util.concurrent.ConcurrentHashMap<>();
        public java.util.Optional<String> fingerprint(String version){return java.util.Optional.ofNullable(values.get(version));}
        public void registerIfAbsent(String version,String fingerprint){values.putIfAbsent(version,fingerprint);}
    };
    final UUID org=UUID.randomUUID(),app=UUID.randomUUID();
    @Test void secretsSurviveRestartAndUseRandomNonces()throws Exception{
        var cipher=new FileSecretCipher(directory,registry);var first=cipher.encrypt(org,app,"TOKEN","private-value");
        assertFalse(first.ciphertext().contains("private-value"));
        assertNotEquals(first.ciphertext(),cipher.encrypt(org,app,"TOKEN","private-value").ciphertext());
        assertEquals("private-value",new FileSecretCipher(directory,registry).decrypt(org,app,"TOKEN",first));
        assertEquals(32,Files.size(directory.resolve("application-secrets-v1.key")));
    }
    @Test void ciphertextCannotBeMovedToAnotherTenantApplicationOrKey(){
        var cipher=new FileSecretCipher(directory,registry);var encrypted=cipher.encrypt(org,app,"TOKEN","secret");
        assertThrows(ApplicationException.class,()->cipher.decrypt(UUID.randomUUID(),app,"TOKEN",encrypted));
        assertThrows(ApplicationException.class,()->cipher.decrypt(org,UUID.randomUUID(),"TOKEN",encrypted));
        assertThrows(ApplicationException.class,()->cipher.decrypt(org,app,"OTHER",encrypted));
    }
    @Test void tamperedCiphertextOrUnknownKeyVersionIsRejected(){
        var cipher=new FileSecretCipher(directory,registry);var encrypted=cipher.encrypt(org,app,"TOKEN","secret");
        byte[] bytes=java.util.Base64.getDecoder().decode(encrypted.ciphertext());bytes[bytes.length-1]^=1;
        assertThrows(ApplicationException.class,()->cipher.decrypt(org,app,"TOKEN",new SecretCipher.Encrypted(java.util.Base64.getEncoder().encodeToString(bytes),"v1")));
        assertThrows(ApplicationException.class,()->cipher.decrypt(org,app,"TOKEN",new SecretCipher.Encrypted(encrypted.ciphertext(),"v2")));
    }
    @Test void missingKeyDoesNotGenerateAReplacement()throws Exception{
        var cipher=new FileSecretCipher(directory,registry);var encrypted=cipher.encrypt(org,app,"TOKEN","secret");
        Files.delete(directory.resolve("application-secrets-v1.key"));
        assertThrows(ApplicationException.class,()->new FileSecretCipher(directory,registry).decrypt(org,app,"TOKEN",encrypted));
        assertFalse(Files.exists(directory.resolve("application-secrets-v1.key")));
        assertThrows(ApplicationException.class,()->new FileSecretCipher(directory,registry).encrypt(org,app,"OTHER","new-secret"));
        assertFalse(Files.exists(directory.resolve("application-secrets-v1.key")));
    }
}
