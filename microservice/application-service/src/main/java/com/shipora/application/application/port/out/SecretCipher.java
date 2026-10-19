package com.shipora.application.application.port.out;

import java.util.UUID;

public interface SecretCipher {
    Encrypted encrypt(UUID organizationId, UUID applicationId, String key, String plaintext);
    String decrypt(UUID organizationId, UUID applicationId, String key, Encrypted encrypted);
    record Encrypted(String ciphertext, String keyVersion) {
        @Override public String toString() { return "EncryptedSecret[redacted]"; }
    }
}
