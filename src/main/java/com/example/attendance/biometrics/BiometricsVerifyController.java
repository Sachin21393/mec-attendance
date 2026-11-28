package com.example.attendance.biometrics;

import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.auth.WorkerPrincipal;
import com.example.attendance.common.UnauthorizedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/biometrics")
public class BiometricsVerifyController {

    private final BiometricsVerificationService verificationService;
    private final RateLimiterService rateLimiterService;

    public BiometricsVerifyController(
            BiometricsVerificationService verificationService,
            RateLimiterService rateLimiterService
    ) {
        this.verificationService = verificationService;
        this.rateLimiterService = rateLimiterService;
    }

    @PostMapping("/verify")
    public BiometricsVerificationService.VerificationOutcome verify(
            @AuthenticationPrincipal WorkerPrincipal principal,
            @Valid @RequestBody VerifyFaceRequest request
    ) {
        if (principal == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "Employee session is required.");
        }
        rateLimiterService.checkLimit("bio-verify:" + principal.workerId(), 60, Duration.ofMinutes(5));
        return verificationService.verify(
                principal.workerId(),
                request.mode(),
                request.targetWorkerCode(),
                request.imageBase64()
        );
    }

    public record VerifyFaceRequest(
            @NotNull(message = "Verification mode is required") BiometricsVerificationService.VerificationMode mode,
            String targetWorkerCode,
            @NotBlank(message = "Face capture payload is required") String imageBase64
    ) {
    }
}
