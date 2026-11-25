package com.example.attendance;

import com.example.attendance.auth.CookieHelper;
import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.biometrics.BiometricEncryptionService;
import com.example.attendance.biometrics.FaceRecognitionProvider;
import com.example.attendance.biometrics.ImagePayloadValidator;
import com.example.attendance.common.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BiometricsVerificationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BiometricEncryptionService encryptionService;

    @Autowired
    private ImagePayloadValidator imageValidator;

    @Autowired
    private FaceRecognitionProvider faceProvider;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setup() {
        rateLimiterService.resetAll();
    }

    @Test
    void encryptsAndDecryptsEmbeddingWithUniqueRandomIvs() {
        float[] vec = new float[128];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = (i - 64) / 100.0f;
        }
        var enc1 = encryptionService.encrypt(vec);
        var enc2 = encryptionService.encrypt(vec);

        assertThat(enc1.iv()).isNotEqualTo(enc2.iv());
        assertThat(enc1.ciphertext()).isNotEqualTo(enc2.ciphertext());
        assertThat(encryptionService.decrypt(enc1.ciphertext(), enc1.iv())).containsExactly(vec);
    }

    @Test
    void validatesRealImageBytesAndRejectsInvalidOrDarkImages() throws Exception {
        assertThatThrownBy(() -> imageValidator.decodeAndValidateImage("not-an-image".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("JPEG or PNG");

        // Too small 50x50 image
        byte[] tinyPng = createSolidPng(50, 50, new Color(140, 140, 140));
        assertThatThrownBy(() -> imageValidator.decodeAndValidateImage(tinyPng))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("160x160");

        // Too dark 200x200 image
        byte[] darkPng = createSolidPng(200, 200, new Color(5, 5, 5));
        assertThatThrownBy(() -> imageValidator.decodeAndValidateImage(darkPng))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Lighting is too low");

        // Valid 220x220 balanced image passes
        byte[] validPng = createSolidPng(220, 220, new Color(130, 140, 150));
        assertThat(imageValidator.decodeAndValidateImage(validPng).getWidth()).isEqualTo(220);
        assertThat(faceProvider.extractEmbedding(validPng).embedding()).hasSize(128);
    }

    @Test
    void verifiesSelfAndProxyFacesWithoutExposingRawSimilarityScores() throws Exception {
        // Register W009 using seeded demo invitation JOIN-W009-DEMO
        String w009Face = Base64.getEncoder().encodeToString("MOCK_FACE_W009".getBytes(StandardCharsets.UTF_8));
        MvcResult regRes = mockMvc.perform(post("/api/v1/workers/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "invitationCode", "JOIN-W009-DEMO",
                                "fullName", "Deepak Kulkarni",
                                "consentGiven", true,
                                "consentVersion", "2026-11-v1",
                                "imageBase64", w009Face
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie workerCookie = regRes.getResponse().getCookie(CookieHelper.WORKER_SESSION_COOKIE);
        assertThat(workerCookie).isNotNull();

        // Self verification succeeds and hides raw similarity score
        MvcResult selfRes = mockMvc.perform(post("/api/v1/biometrics/verify")
                        .with(csrf())
                        .cookie(workerCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mode", "SELF",
                                "imageBase64", w009Face
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationToken").isNotEmpty())
                .andExpect(jsonPath("$.matchedWorkerCode").value("W009"))
                .andExpect(jsonPath("$.mockMode").value(true))
                .andExpect(jsonPath("$.similarity").doesNotExist())
                .andExpect(jsonPath("$.matchConfidence").doesNotExist())
                .andReturn();
        JsonNode selfJson = objectMapper.readTree(selfRes.getResponse().getContentAsString());
        assertThat(selfJson.get("verificationToken").asText()).isNotBlank();

        // Quality failure markers map to friendly errors
        String noFace = Base64.getEncoder().encodeToString("NO_FACE_TEST".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/biometrics/verify")
                        .with(csrf())
                        .cookie(workerCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mode", "SELF",
                                "imageBase64", noFace
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_FACE_DETECTED"));

        // Mismatched face fails cleanly
        String wrongFace = Base64.getEncoder().encodeToString("SYNTHETIC_UNKNOWN_PERSON".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/biometrics/verify")
                        .with(csrf())
                        .cookie(workerCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mode", "SELF",
                                "imageBase64", wrongFace
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FACE_NOT_RECOGNIZED"));

        // Proxy verification identifies seeded W001 without revealing raw score
        String w001Face = Base64.getEncoder().encodeToString("MOCK_FACE_W001".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(post("/api/v1/biometrics/verify")
                        .with(csrf())
                        .cookie(workerCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "mode", "PROXY",
                                "imageBase64", w001Face
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PROXY"))
                .andExpect(jsonPath("$.matchedWorkerCode").value("W001"))
                .andExpect(jsonPath("$.matchedWorkerName").value("Aarav Sharma"))
                .andExpect(jsonPath("$.similarity").doesNotExist());
    }

    private static byte[] createSolidPng(int width, int height, Color color) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
