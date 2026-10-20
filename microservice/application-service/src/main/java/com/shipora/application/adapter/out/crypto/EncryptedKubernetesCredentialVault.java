package com.shipora.application.adapter.out.crypto;

import com.shipora.application.application.port.out.*;
import com.shipora.application.domain.*;
import com.shipora.application.domain.KubernetesTarget.Credentials;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;

@RequiredArgsConstructor
public final class EncryptedKubernetesCredentialVault implements KubernetesCredentialVault {
    private static final String CONTEXT = "KUBERNETES_ACCESS_V1";
    private final SecretCipher cipher;
    private final JsonMapper json;
    @Override public Credentials encrypt(UUID org, UUID id, KubernetesAccess access) {
        var encrypted = cipher.encrypt(org,id,CONTEXT,json.writeValueAsString(access));
        return new Credentials(encrypted.ciphertext(),encrypted.keyVersion());
    }
    @Override public KubernetesAccess decrypt(UUID org, UUID id, Credentials encrypted) {
        try {
            return json.readValue(cipher.decrypt(org,id,CONTEXT,new SecretCipher.Encrypted(encrypted.ciphertext(),encrypted.keyVersion())),KubernetesAccess.class);
        } catch (RuntimeException e) {
            throw new ApplicationException(ApplicationException.Kind.UNAVAILABLE,"Kubernetes credentials could not be decrypted. Check the persisted encryption key.");
        }
    }
}
