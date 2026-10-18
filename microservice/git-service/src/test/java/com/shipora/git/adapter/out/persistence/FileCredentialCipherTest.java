package com.shipora.git.adapter.out.persistence;

import com.shipora.git.domain.GitException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FileCredentialCipherTest {
    @TempDir Path directory;
    @Test void credentialsSurviveRestartWithoutPlaintextInCiphertextOrKeyFile() throws Exception {
        var cipher = new FileCredentialCipher(directory);
        String encrypted = cipher.encrypt("private-key-and-client-secret");
        assertFalse(encrypted.contains("private-key")); assertTrue(encrypted.startsWith("v1:"));
        assertEquals("private-key-and-client-secret",new FileCredentialCipher(directory).decrypt(encrypted));
        assertEquals(32,Files.size(directory.resolve("github-credentials.key")));
    }
    @Test void randomizedNoncesPreventIdenticalCiphertexts() {
        var cipher = new FileCredentialCipher(directory);
        assertNotEquals(cipher.encrypt("same secret"),cipher.encrypt("same secret"));
    }
    @Test void tamperedCiphertextIsRejected() {
        var cipher = new FileCredentialCipher(directory); String value=cipher.encrypt("secret");
        char last=value.charAt(8)=='A'?'B':'A';
        String bad=value.substring(0,8)+last+value.substring(9);
        assertThrows(GitException.class,()->cipher.decrypt(bad));
    }
    @Test void losingKeyDoesNotSilentlyGenerateReplacement() throws Exception {
        var cipher = new FileCredentialCipher(directory); String value=cipher.encrypt("secret");
        Files.delete(directory.resolve("github-credentials.key"));
        assertThrows(GitException.class,()->new FileCredentialCipher(directory).decrypt(value));
        assertFalse(Files.exists(directory.resolve("github-credentials.key")));
    }
    @Test void wrongKeyOrUnknownEnvelopeVersionIsRejected() {
        String value=new FileCredentialCipher(directory.resolve("one")).encrypt("secret");
        var other=new FileCredentialCipher(directory.resolve("two")); other.encrypt("different");
        assertThrows(GitException.class,()->other.decrypt(value));
        assertThrows(GitException.class,()->other.decrypt("v2:unknown"));
    }
}
