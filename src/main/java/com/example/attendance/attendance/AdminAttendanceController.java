package com.example.attendance.attendance;

import com.example.attendance.common.ValidationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminAttendanceController {

    private final MonthlyAttendanceQueryService queryService;

    public AdminAttendanceController(MonthlyAttendanceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/dashboard")
    public MonthlyAttendanceQueryService.DashboardSummary dashboard(
            @RequestParam(required = false) String month
    ) {
        return queryService.getDashboard(parseMonthOrDefault(month));
    }

    @GetMapping("/attendance")
    public MonthlyAttendanceQueryService.MonthlyGridResponse monthlyGrid(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String team,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return queryService.getMonthlyGrid(parseMonthOrDefault(month), query, team, page, size);
    }

    @GetMapping("/attendance/{id}")
    public MonthlyAttendanceQueryService.AttendanceDetailDto detail(@PathVariable UUID id) {
        return queryService.getRecordDetail(id);
    }

    public static YearMonth parseMonthOrDefault(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException("INVALID_MONTH", "Month must use YYYY-MM format.");
        }
    }
}
