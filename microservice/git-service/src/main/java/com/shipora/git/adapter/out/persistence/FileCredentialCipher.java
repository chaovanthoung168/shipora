package com.shipora.git.adapter.out.persistence;

import com.shipora.git.domain.GitException;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import static com.shipora.git.domain.GitException.Kind.NOT_CONFIGURED;

/** The key is generated per installation and persisted outside the database. Never package it in an image. */
public final class FileCredentialCipher implements CredentialCipher {
    private static final byte[] AAD = "shipora:github-app:v1".getBytes(StandardCharsets.US_ASCII);
    private final Path directory;
    private final SecureRandom random = new SecureRandom();
    public FileCredentialCipher(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    @Override public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception e) { throw unavailable(); }
    }
    @Override public String decrypt(String ciphertext) {
        try {
            if (!ciphertext.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] envelope = Base64.getDecoder().decode(ciphertext.substring(3));
            if (envelope.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"),
                    new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 0, 12)));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(envelope, 12, envelope.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw unavailable(); }
    }
    private synchronized byte[] key(boolean create) throws Exception {
        Files.createDirectories(directory);
        Path path = directory.resolve("github-credentials.key");
        try (var lock = FileChannel.open(directory.resolve("github-credentials.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = lock.lock()) {
            if (!Files.exists(path)) {
                if (!create) throw new IllegalStateException();
                byte[] bytes = new byte[32]; random.nextBytes(bytes);
                try { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
                catch (UnsupportedOperationException e) { Files.createFile(path); }
                try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length != 32) throw new IllegalStateException();
            return bytes;
        }
    }
    private GitException unavailable() {
        return new GitException(NOT_CONFIGURED, "GitHub credential storage is unavailable. Check the persistent Shipora data volume and encryption key.");
    }
}
