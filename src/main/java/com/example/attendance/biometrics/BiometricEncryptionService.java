package com.example.attendance.biometrics;

import com.example.attendance.common.AttendanceProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
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
        byte[] keyBytes = Base64.getDecoder().decode(keyB64.trim());
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException("BIOMETRICS_ENCRYPTION_KEY_B64 must decode to 16, 24, or 32 bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.keyVersion = properties.getBiometrics().getKeyVersion();
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
