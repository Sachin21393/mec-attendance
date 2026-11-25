package com.example.attendance.attendance;

import com.example.attendance.auth.RateLimiterService;
import com.example.attendance.auth.WorkerPrincipal;
import com.example.attendance.common.UnauthorizedException;
import com.example.attendance.common.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    private final RateLimiterService rateLimiterService;

    public AttendanceController(AttendanceCheckInService checkInService, RateLimiterService rateLimiterService) {
        this.checkInService = checkInService;
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
                effectiveIdemKey
        );
    }

    public record CheckInRequest(
            @NotBlank(message = "Verification token is required") String verificationToken,
            String idempotencyKey
    ) {
    }
}
