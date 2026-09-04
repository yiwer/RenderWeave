package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.input.InferenceStorageException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

final class EnvelopeCrypto {
    static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    static final int TAG_BYTES = 16;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private EnvelopeCrypto() { }

    static SealedBytes seal(SecretKey key, byte[] nonce, byte[] aad, byte[] plaintext) {
        requireKey(key);
        requireLength(nonce, NONCE_BYTES, "nonce");
        if (aad == null || plaintext == null) throw new IllegalArgumentException("AAD and plaintext are required");
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(aad);
            var combined = cipher.doFinal(plaintext);
            var split = combined.length - TAG_BYTES;
            return new SealedBytes(
                    Arrays.copyOf(combined, split), Arrays.copyOfRange(combined, split, combined.length)
            );
        } catch (GeneralSecurityException failure) {
            throw new InferenceStorageException(
                    "STORAGE_ENCRYPTION_UNAVAILABLE", "Artifact encryption is unavailable", failure
            );
        }
    }

    static byte[] open(SecretKey key, byte[] nonce, byte[] aad, byte[] ciphertext, byte[] tag) {
        requireKey(key);
        requireLength(nonce, NONCE_BYTES, "nonce");
        requireLength(tag, TAG_BYTES, "tag");
        if (aad == null || ciphertext == null) throw new IllegalArgumentException("AAD and ciphertext are required");
        var combined = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
        System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);
        try {
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(combined);
        } catch (AEADBadTagException authenticationFailure) {
            throw new InferenceStorageException(
                    "STORAGE_ARTIFACT_AUTHENTICATION_FAILED",
                    "Encrypted artifact authentication failed",
                    authenticationFailure
            );
        } catch (GeneralSecurityException failure) {
            throw new InferenceStorageException(
                    "STORAGE_DECRYPTION_UNAVAILABLE", "Artifact decryption is unavailable", failure
            );
        }
    }

    static byte[] payloadAad(String artifactId) {
        return ("renderweave-artifact-payload/1.0\u0000" + artifactId)
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] wrappingAad(String artifactId, String kekId) {
        return ("renderweave-artifact-dek-wrap/1.0\u0000" + artifactId + "\u0000" + kekId)
                .getBytes(StandardCharsets.UTF_8);
    }

    static SecretKey dek(byte[] bytes) {
        requireLength(bytes, KEY_BYTES, "DEK");
        return new SecretKeySpec(bytes.clone(), "AES");
    }

    private static void requireKey(SecretKey key) {
        if (key == null || !"AES".equals(key.getAlgorithm())) {
            throw new IllegalArgumentException("AES-256 key is required");
        }
        var encoded = key.getEncoded();
        try {
            if (encoded == null || encoded.length != KEY_BYTES) {
                throw new IllegalArgumentException("AES-256 key is required");
            }
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    private static void requireLength(byte[] value, int expected, String name) {
        if (value == null || value.length != expected) {
            throw new IllegalArgumentException(name + " must contain exactly " + expected + " bytes");
        }
    }

    record SealedBytes(byte[] ciphertext, byte[] tag) {
        SealedBytes {
            ciphertext = ciphertext.clone();
            tag = tag.clone();
        }

        @Override
        public byte[] ciphertext() { return ciphertext.clone(); }

        @Override
        public byte[] tag() { return tag.clone(); }
    }
}
