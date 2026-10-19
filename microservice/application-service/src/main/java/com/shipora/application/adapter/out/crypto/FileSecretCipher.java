package com.shipora.application.adapter.out.crypto;

import com.shipora.application.application.port.out.SecretCipher;
import com.shipora.application.application.port.out.SecretKeyRegistry;
import com.shipora.application.domain.ApplicationException;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.*;
import static com.shipora.application.domain.ApplicationException.Kind.UNAVAILABLE;

public final class FileSecretCipher implements SecretCipher {
    private final Path directory;
    private final SecretKeyRegistry registry;
    private final SecureRandom random=new SecureRandom();
    public FileSecretCipher(Path directory,SecretKeyRegistry registry){this.directory=directory.toAbsolutePath().normalize();this.registry=registry;}
    @Override public Encrypted encrypt(UUID org,UUID app,String name,String plaintext){
        try{
            byte[] nonce=new byte[12];random.nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(true),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(org,app,name));
            byte[] encrypted=cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array()),"v1");
        }catch(Exception e){throw unavailable();}
    }
    @Override public String decrypt(UUID org,UUID app,String name,Encrypted value){
        try{
            if(!"v1".equals(value.keyVersion()))throw new IllegalArgumentException();
            byte[] bytes=Base64.getDecoder().decode(value.ciphertext());if(bytes.length<28)throw new IllegalArgumentException();
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(false),"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));cipher.updateAAD(aad(org,app,name));
            return new String(cipher.doFinal(bytes,12,bytes.length-12),StandardCharsets.UTF_8);
        }catch(Exception e){throw unavailable();}
    }
    private byte[] aad(UUID org,UUID app,String name){return ("shipora:application-secret:v1:"+org+":"+app+":"+name).getBytes(StandardCharsets.UTF_8);}
    private synchronized byte[] key(boolean create)throws Exception{
        Files.createDirectories(directory);var path=directory.resolve("application-secrets-v1.key");
        try(var channel=FileChannel.open(directory.resolve("application-secrets.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()){
            var registered=registry.fingerprint("v1");
            if(!Files.exists(path)){
                if(!create || registered.isPresent())throw new IllegalStateException();
                byte[] key=new byte[32];random.nextBytes(key);
                try{Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}
                catch(UnsupportedOperationException e){Files.createFile(path);}
                try(var file=FileChannel.open(path,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(key);while(buffer.hasRemaining())file.write(buffer);file.force(true);}
            }
            byte[] key=Files.readAllBytes(path);if(key.length!=32)throw new IllegalStateException();
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
            if(registered.isEmpty()) {
                if(!create)throw new IllegalStateException();
                registry.registerIfAbsent("v1",fingerprint);
            }
            if(!registry.fingerprint("v1").orElseThrow().equals(fingerprint))throw new IllegalStateException();
            return key;
        }
    }
    private ApplicationException unavailable(){return new ApplicationException(UNAVAILABLE,"Application secret storage is unavailable. Check the persistent data volume and encryption key.");}
}
