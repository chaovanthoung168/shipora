package com.shipora.git.adapter.out.github;

import java.io.ByteArrayOutputStream;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** GitHub manifests return PKCS#1; environment overrides may also use PKCS#8. */
final class RsaPrivateKeys {
    private RsaPrivateKeys() {}
    static PrivateKey parse(String pem) throws Exception {
        if (pem == null || pem.length() > 32768) throw new IllegalArgumentException();
        byte[] der;
        if (pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            byte[] pkcs1 = decode(pem, "RSA PRIVATE KEY");
            byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
            var content = new ByteArrayOutputStream();
            content.writeBytes(new byte[]{0x02, 0x01, 0x00}); content.writeBytes(algorithm); content.writeBytes(wrap(0x04, pkcs1));
            der = wrap(0x30, content.toByteArray());
        } else der = decode(pem, "PRIVATE KEY");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        if (((RSAPrivateKey) key).getModulus().bitLength() < 2048) throw new IllegalArgumentException();
        return key;
    }
    private static byte[] decode(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----", end = "-----END " + label + "-----";
        if (!pem.strip().startsWith(begin) || !pem.strip().endsWith(end)) throw new IllegalArgumentException();
        return Base64.getDecoder().decode(pem.replace(begin, "").replace(end, "").replaceAll("\\s", ""));
    }
    private static byte[] wrap(int tag, byte[] data) {
        var result = new ByteArrayOutputStream(); result.write(tag);
        if (data.length < 128) result.write(data.length);
        else {
            int count = (32 - Integer.numberOfLeadingZeros(data.length) + 7) / 8;
            result.write(0x80 | count);
            for (int i = count - 1; i >= 0; i--) result.write(data.length >>> (8 * i) & 0xff);
        }
        result.writeBytes(data); return result.toByteArray();
    }
}
