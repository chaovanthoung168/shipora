package com.shipora.application.adapter.out.crypto;

import com.shipora.application.application.port.out.*;
import com.shipora.application.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EncryptedKubernetesCredentialVaultTest {
    @TempDir Path directory;
    final UUID org=UUID.randomUUID(),target=UUID.randomUUID();
    final SecretKeyRegistry registry=new SecretKeyRegistry() {
        final Map<String,String> entries=new HashMap<>();
        public Optional<String> fingerprint(String version){return Optional.ofNullable(entries.get(version));}
        public void registerIfAbsent(String version,String fingerprint){entries.putIfAbsent(version,fingerprint);}
    };
    EncryptedKubernetesCredentialVault vault(){return new EncryptedKubernetesCredentialVault(new FileSecretCipher(directory,registry),JsonMapper.builder().build());}
    @Test void persistsEncryptedTokenAcrossRestartAndBindsItToTenantAndTarget() {
        var access=new KubernetesAccess("https://cluster","shipora","private-token","Y2E=");var encrypted=vault().encrypt(org,target,access);
        assertFalse(encrypted.ciphertext().contains("private-token"));assertFalse(encrypted.toString().contains("ciphertext"));
        assertEquals(access,vault().decrypt(org,target,encrypted));
        assertThrows(ApplicationException.class,()->vault().decrypt(UUID.randomUUID(),target,encrypted));
        assertThrows(ApplicationException.class,()->vault().decrypt(org,UUID.randomUUID(),encrypted));
        assertThrows(ApplicationException.class,()->new FileSecretCipher(directory,registry).decrypt(org,target,"TOKEN",new SecretCipher.Encrypted(encrypted.ciphertext(),encrypted.keyVersion())));
    }
}
