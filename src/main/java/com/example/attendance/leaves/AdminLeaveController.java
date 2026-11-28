package com.example.attendance.leaves;

import com.example.attendance.common.ValidationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/v1/admin/leaves")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLeaveController {

    private final LeaveService leaveService;

    public AdminLeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }

    @GetMapping
    public LeaveService.AdminLeaveOverviewResponse overview(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String team
    ) {
        YearMonth ym = parseMonthOrCurrent(month);
        return leaveService.getAdminLeaveOverview(ym, query, team);
    }

    private static YearMonth parseMonthOrCurrent(String raw) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException("INVALID_MONTH", "Month must use YYYY-MM format.");
        }
    }
}
