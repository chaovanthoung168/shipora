package com.shipora.git.adapter.out.persistence;

public interface CredentialCipher {
    String encrypt(String plaintext);
    String decrypt(String ciphertext);
}
