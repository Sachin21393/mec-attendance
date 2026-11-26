package com.example.attendance;

import com.example.attendance.biometrics.BiometricEncryptionService;
import com.example.attendance.common.AttendanceProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BiometricEncryptionServiceTest {

    @Test
    void supportsBase64AndGeneratedHighEntropySecretsWhileRejectingShortKeys() {
        AttendanceProperties stdProps = new AttendanceProperties();
        stdProps.getBiometrics().setEncryptionKeyB64("MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=");
        BiometricEncryptionService stdSvc = new BiometricEncryptionService(stdProps);

        float[] vec = new float[] {0.1f, -0.25f, 0.75f};
        BiometricEncryptionService.EncryptedTemplate enc1 = stdSvc.encrypt(vec);
        assertThat(stdSvc.decrypt(enc1.ciphertext(), enc1.iv())).containsExactly(vec);

        AttendanceProperties genProps = new AttendanceProperties();
        genProps.getBiometrics().setEncryptionKeyB64("render-generated-random-secret-256bit");
        BiometricEncryptionService genSvc = new BiometricEncryptionService(genProps);
        BiometricEncryptionService.EncryptedTemplate enc2 = genSvc.encrypt(vec);
        assertThat(genSvc.decrypt(enc2.ciphertext(), enc2.iv())).containsExactly(vec);

        AttendanceProperties badProps = new AttendanceProperties();
        badProps.getBiometrics().setEncryptionKeyB64("too-short");
        assertThatThrownBy(() -> new BiometricEncryptionService(badProps))
                .isInstanceOf(IllegalStateException.class);
    }
}
