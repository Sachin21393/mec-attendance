package com.example.attendance.settings;

import com.example.attendance.attendance.AdminAttendanceController;
import com.example.attendance.auth.AdminPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSettingsController {

    private final AdminSettingsService settingsService;

    public AdminSettingsController(AdminSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public AdminSettingsService.SettingsDto getSettings(@AuthenticationPrincipal AdminPrincipal admin) {
        return settingsService.getSettings(admin);
    }

    @PutMapping("/settings")
    public AdminSettingsService.SettingsDto updateSettings(
            @AuthenticationPrincipal AdminPrincipal admin,
            @Valid @RequestBody AdminSettingsService.UpdateSettingsRequest request
    ) {
        return settingsService.updateSettings(admin, request);
    }

    @GetMapping("/calendar")
    public AdminSettingsService.CalendarMonthDto getCalendar(@RequestParam(required = false) String month) {
        YearMonth ym = AdminAttendanceController.parseMonthOrDefault(month);
        return settingsService.getCalendarMonth(ym);
    }

    @PutMapping("/calendar")
    public AdminSettingsService.CalendarMonthDto updateCalendar(
            @AuthenticationPrincipal AdminPrincipal admin,
            @RequestParam(required = false) String month,
            @Valid @RequestBody AdminSettingsService.UpdateCalendarRequest request
    ) {
        YearMonth ym = AdminAttendanceController.parseMonthOrDefault(month);
        return settingsService.updateCalendarMonth(admin, ym, request);
    }
}
