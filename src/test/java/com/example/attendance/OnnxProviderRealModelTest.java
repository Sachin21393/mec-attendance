package com.example.attendance;

import com.example.attendance.biometrics.ImagePayloadValidator;
import com.example.attendance.biometrics.OnnxFaceRecognitionProvider;
import com.example.attendance.common.AttendanceProperties;
import com.example.attendance.common.ValidationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxProviderRealModelTest {

    @Test
    void loadsRealYunetAndSfaceOnnxModelsAndRunsDetection() throws Exception {
        Path yunet = Path.of("models/face_detection_yunet_2023mar.onnx");
        Path sface = Path.of("models/face_recognition_sface_2021dec.onnx");
        Assumptions.assumeTrue(Files.isRegularFile(yunet) && Files.isRegularFile(sface), "ONNX models not present");

        AttendanceProperties props = new AttendanceProperties();
        props.getBiometrics().setProvider("onnx");
        props.getBiometrics().setYunetModelPath(yunet.toString());
        props.getBiometrics().setSfaceModelPath(sface.toString());
        props.getBiometrics().setEmbeddingDimension(128);

        OnnxFaceRecognitionProvider provider = new OnnxFaceRecognitionProvider(props, new ImagePayloadValidator(props));
        try {
            assertThat(provider.isMock()).isFalse();
            assertThat(provider.embeddingDimension()).isEqualTo(128);
            assertThat(provider.modelVersion()).isEqualTo("yunet-2023mar+sface-2021dec-rgb255");

            // Solid gray 320x320 image runs YuNet inference cleanly and rejects with NO_FACE_DETECTED
            BufferedImage img = new BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(140, 140, 140));
            g.fillRect(0, 0, 320, 320);
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);

            assertThatThrownBy(() -> provider.extractEmbedding(baos.toByteArray()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("No face detected");
        } finally {
            provider.close();
        }
    }
}
