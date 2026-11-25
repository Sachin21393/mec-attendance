package com.example.attendance.biometrics;

import com.example.attendance.common.AppException;
import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.common.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class ImagePayloadValidator {

    private static final int MIN_WIDTH = 160;
    private static final int MIN_HEIGHT = 160;
    private static final int MAX_WIDTH = 4096;
    private static final int MAX_HEIGHT = 4096;
    private static final double MIN_MEAN_LUMA = 20.0;
    private static final double MAX_MEAN_LUMA = 245.0;

    private final AttendanceProperties properties;

    public ImagePayloadValidator(AttendanceProperties properties) {
        this.properties = properties;
    }

    public byte[] decodeBase64(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new ValidationException("BIOMETRIC_IMAGE_REQUIRED", "A face image capture is required.");
        }
        String payload = imageBase64.trim();
        int commaIdx = payload.indexOf(',');
        if (payload.startsWith("data:") && commaIdx > 0) {
            payload = payload.substring(commaIdx + 1);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("BIOMETRIC_IMAGE_INVALID", "Face capture payload is not valid base64.");
        }
        if (bytes.length == 0) {
            throw new ValidationException("BIOMETRIC_IMAGE_REQUIRED", "Face capture payload is empty.");
        }
        if (bytes.length > properties.getBiometrics().getMaxImageBytes()) {
            throw new AppException("PAYLOAD_TOO_LARGE", "Face image exceeds maximum allowed size.", HttpStatus.PAYLOAD_TOO_LARGE);
        }
        return bytes;
    }

    public BufferedImage decodeAndValidateImage(byte[] imageBytes) {
        if (!hasSupportedMagicBytes(imageBytes)) {
            throw new ValidationException("UNSUPPORTED_IMAGE_FORMAT", "Face capture must be a valid JPEG or PNG image.");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new ValidationException("BIOMETRIC_IMAGE_INVALID", "Unable to decode face image.");
        }
        if (image == null) {
            throw new ValidationException("BIOMETRIC_IMAGE_INVALID", "Unable to decode face image.");
        }
        int w = image.getWidth();
        int h = image.getHeight();
        if (w < MIN_WIDTH || h < MIN_HEIGHT || w > MAX_WIDTH || h > MAX_HEIGHT) {
            throw new ValidationException(
                    "INVALID_IMAGE_DIMENSIONS",
                    "Image dimensions must be between 160x160 and 4096x4096 pixels."
            );
        }
        double meanLuma = computeMeanLuminance(image);
        if (meanLuma < MIN_MEAN_LUMA) {
            throw new ValidationException("POOR_LIGHTING", "Lighting is too low. Move to a brighter area and try again.");
        }
        if (meanLuma > MAX_MEAN_LUMA) {
            throw new ValidationException("POOR_LIGHTING", "Image is overexposed. Avoid harsh backlight and try again.");
        }
        return image;
    }

    public boolean isSyntheticMockTokenPayload(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 512) {
            return false;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("MOCK_FACE_") || text.startsWith("SYNTHETIC_")
                || text.contains("NO_FACE") || text.contains("MULTI_FACE")
                || text.contains("LOW_LIGHT") || text.contains("LIVENESS_FAIL");
    }

    private static boolean hasSupportedMagicBytes(byte[] bytes) {
        if (bytes.length < 8) {
            return false;
        }
        boolean isJpeg = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
        boolean isPng = (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
        return isJpeg || isPng;
    }

    private static double computeMeanLuminance(BufferedImage image) {
        int stepX = Math.max(1, image.getWidth() / 32);
        int stepY = Math.max(1, image.getHeight() / 32);
        double sum = 0.0;
        long count = 0;
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }
}
