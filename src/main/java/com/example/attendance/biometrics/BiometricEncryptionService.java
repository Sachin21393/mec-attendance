package com.example.attendance.biometrics;

import com.example.attendance.common.AttendanceProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class BiometricEncryptionService {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey secretKey;
    private final String keyVersion;
    private final SecureRandom secureRandom = new SecureRandom();

    public BiometricEncryptionService(AttendanceProperties properties) {
        String keyB64 = properties.getBiometrics().getEncryptionKeyB64();
        if (keyB64 == null || keyB64.isBlank()) {
            throw new IllegalStateException("BIOMETRICS_ENCRYPTION_KEY_B64 must be configured");
        }
        byte[] keyBytes = resolveAesKeyBytes(keyB64.trim());
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.keyVersion = properties.getBiometrics().getKeyVersion();
    }

    private static byte[] resolveAesKeyBytes(String rawSecret) {
        try {
            byte[] std = Base64.getDecoder().decode(rawSecret);
            if (std.length == 16 || std.length == 24 || std.length == 32) {
                return std;
            }
        } catch (IllegalArgumentException ignored) {
            // Try URL-safe base64 or high-entropy generated secret fallback below
        }
        try {
            byte[] urlSafe = Base64.getUrlDecoder().decode(rawSecret);
            if (urlSafe.length == 16 || urlSafe.length == 24 || urlSafe.length == 32) {
                return urlSafe;
            }
        } catch (IllegalArgumentException ignored) {
            // Try high-entropy secret derivation below
        }
        if (rawSecret.length() >= 24) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(rawSecret.getBytes(StandardCharsets.UTF_8));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Failed to derive AES-256 key", e);
            }
        }
        throw new IllegalStateException("BIOMETRICS_ENCRYPTION_KEY_B64 must decode to 16, 24, or 32 bytes (or be a secret >= 24 chars)");
    }

    public EncryptedTemplate encrypt(float[] embedding) {
        byte[] plaintext = toBytes(embedding);
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptedTemplate(ciphertext, iv, keyVersion);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt biometric template", e);
        }
    }

    public float[] decrypt(byte[] ciphertext, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return toFloats(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt biometric template", e);
        }
    }

    private static byte[] toBytes(float[] floats) {
        ByteBuffer buf = ByteBuffer.allocate(floats.length * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (float f : floats) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    private static float[] toFloats(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int count = bytes.length / Float.BYTES;
        float[] floats = new float[count];
        for (int i = 0; i < count; i++) {
            floats[i] = buf.getFloat();
        }
        return floats;
    }

    public record EncryptedTemplate(byte[] ciphertext, byte[] iv, String keyVersion) {
    }
}
