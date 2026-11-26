package com.example.attendance.attendance;

import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.auth.WorkerPrincipal;
import com.example.attendance.biometrics.BiometricsVerificationService;
import com.example.attendance.common.UnauthorizedException;
import com.example.attendance.common.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

    private final AttendanceCheckInService checkInService;
    private final BiometricsVerificationService verificationService;
    private final RateLimiterService rateLimiterService;

    public AttendanceController(
            AttendanceCheckInService checkInService,
            BiometricsVerificationService verificationService,
            RateLimiterService rateLimiterService
    ) {
        this.checkInService = checkInService;
        this.verificationService = verificationService;
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping("/today")
    public AttendanceCheckInService.TodayStatusResponse today(@AuthenticationPrincipal WorkerPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "Worker session is required.");
        }
        return checkInService.getTodayStatus(principal.workerId());
    }

    @GetMapping("/history")
    public List<AttendanceCheckInService.WorkerHistoryEntry> history(
            @AuthenticationPrincipal WorkerPrincipal principal,
            @RequestParam(required = false) String month
    ) {
        if (principal == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "Worker session is required.");
        }
        YearMonth ym;
        if (month == null || month.isBlank()) {
            ym = YearMonth.now();
        } else {
            try {
                ym = YearMonth.parse(month.trim());
            } catch (DateTimeParseException e) {
                throw new ValidationException("INVALID_MONTH", "Month must use YYYY-MM format.");
            }
        }
        return checkInService.getWorkerHistory(principal.workerId(), ym);
    }

    @PostMapping("/check-in")
    public AttendanceCheckInService.CheckInResponse checkIn(
            @AuthenticationPrincipal WorkerPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey,
            @Valid @RequestBody CheckInRequest request
    ) {
        if (principal == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "Worker session is required.");
        }
        rateLimiterService.checkLimit("check-in:" + principal.workerId(), 20, Duration.ofMinutes(5));
        String effectiveIdemKey = (request.idempotencyKey() != null && !request.idempotencyKey().isBlank())
                ? request.idempotencyKey()
                : headerIdempotencyKey;
        return checkInService.checkIn(
                principal.workerId(),
                principal.sessionId(),
                request.verificationToken(),
                effectiveIdemKey,
                request.latitude(),
                request.longitude()
        );
    }

    @GetMapping("/kiosk/status")
    public AttendanceCheckInService.KioskStatusResponse kioskStatus() {
        return checkInService.getKioskStatus();
    }

    @PostMapping("/kiosk/location-check")
    public AttendanceCheckInService.GeofenceCheckResult kioskLocationCheck(
            @RequestBody(required = false) LocationCheckRequest request,
            HttpServletRequest httpRequest
    ) {
        String key = "kiosk-loc:" + (httpRequest.getRemoteAddr() != null ? httpRequest.getRemoteAddr() : "unknown");
        rateLimiterService.checkLimit(key, 150, Duration.ofMinutes(5));
        Double lat = request != null ? request.latitude() : null;
        Double lng = request != null ? request.longitude() : null;
        return checkInService.verifyWithinGeofence(lat, lng);
    }

    @PostMapping("/kiosk/verify")
    public BiometricsVerificationService.KioskVerificationOutcome kioskVerify(
            @Valid @RequestBody KioskVerifyRequest request,
            HttpServletRequest httpRequest
    ) {
        String key = "kiosk-verify:" + (httpRequest.getRemoteAddr() != null ? httpRequest.getRemoteAddr() : "unknown");
        rateLimiterService.checkLimit(key, 150, Duration.ofMinutes(5));
        checkInService.verifyWithinGeofence(request.latitude(), request.longitude());
        return verificationService.verifyKiosk(request.workerCode(), request.imageBase64());
    }

    @PostMapping("/kiosk/check-in")
    public AttendanceCheckInService.CheckInResponse kioskCheckIn(
            @RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey,
            @Valid @RequestBody CheckInRequest request,
            HttpServletRequest httpRequest
    ) {
        String key = "kiosk-checkin:" + (httpRequest.getRemoteAddr() != null ? httpRequest.getRemoteAddr() : "unknown");
        rateLimiterService.checkLimit(key, 150, Duration.ofMinutes(5));
        String effectiveIdemKey = (request.idempotencyKey() != null && !request.idempotencyKey().isBlank())
                ? request.idempotencyKey()
                : headerIdempotencyKey;
        return checkInService.kioskCheckIn(
                request.verificationToken(),
                effectiveIdemKey,
                request.latitude(),
                request.longitude()
        );
    }

    /**
     * Single-round-trip kiosk verification + check-in for fast mobile attendance marking.
     */
    @PostMapping("/kiosk/mark")
    public AttendanceCheckInService.CheckInResponse kioskMark(
            @RequestHeader(value = "Idempotency-Key", required = false) String headerIdempotencyKey,
            @Valid @RequestBody KioskMarkRequest request,
            HttpServletRequest httpRequest
    ) {
        String key = "kiosk-mark:" + (httpRequest.getRemoteAddr() != null ? httpRequest.getRemoteAddr() : "unknown");
        rateLimiterService.checkLimit(key, 150, Duration.ofMinutes(5));
        AttendanceActionType action = request.actionType() != null ? request.actionType() : AttendanceActionType.LOGIN;
        String effectiveIdemKey = (request.idempotencyKey() != null && !request.idempotencyKey().isBlank())
                ? request.idempotencyKey()
                : headerIdempotencyKey;
        if (effectiveIdemKey == null || effectiveIdemKey.isBlank()) {
            effectiveIdemKey = java.util.UUID.randomUUID().toString();
        }
        var replay = checkInService.findIdempotentReplay(effectiveIdemKey, action);
        if (replay.isPresent()) {
            return replay.get();
        }
        checkInService.verifyWithinGeofence(request.latitude(), request.longitude());
        BiometricsVerificationService.KioskVerificationOutcome verified =
                verificationService.verifyKiosk(request.workerCode(), request.imageBase64());
        return action == AttendanceActionType.LOGOUT
                ? checkInService.kioskCheckOut(verified.verificationToken(), effectiveIdemKey, request.latitude(), request.longitude())
                : checkInService.kioskCheckIn(verified.verificationToken(), effectiveIdemKey, request.latitude(), request.longitude());
    }

    public record LocationCheckRequest(
            Double latitude,
            Double longitude
    ) {
    }

    public record CheckInRequest(
            @NotBlank(message = "Verification token is required") String verificationToken,
            String idempotencyKey,
            Double latitude,
            Double longitude
    ) {
    }

    public record KioskVerifyRequest(
            @Size(max = 40) String workerCode,
            @NotBlank(message = "Image is required") String imageBase64,
            Double latitude,
            Double longitude
    ) {
    }

    public record KioskMarkRequest(
            @Size(max = 40) String workerCode,
            @NotBlank(message = "Image is required") String imageBase64,
            String idempotencyKey,
            AttendanceActionType actionType,
            Double latitude,
            Double longitude
    ) {
    }
}
