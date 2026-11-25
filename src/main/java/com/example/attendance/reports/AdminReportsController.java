package com.example.attendance.reports;

import com.example.attendance.attendance.AdminAttendanceController;
import com.example.attendance.auth.AdminPrincipal;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;

@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportsController {

    private final AdminReportsService reportsService;

    public AdminReportsController(AdminReportsService reportsService) {
        this.reportsService = reportsService;
    }

    @GetMapping("/preview")
    public AdminReportsService.ReportPreviewResponse preview(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String team
    ) {
        YearMonth ym = AdminAttendanceController.parseMonthOrDefault(month);
        return reportsService.preview(ym, query, team);
    }

    @GetMapping("/attendance.csv")
    public ResponseEntity<byte[]> exportCsv(
            @AuthenticationPrincipal AdminPrincipal admin,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String team
    ) {
        YearMonth ym = AdminAttendanceController.parseMonthOrDefault(month);
        byte[] csvBytes = reportsService.generateCsv(admin, ym, query, team);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("attendance_" + ym + ".csv", StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(csvBytes);
    }
}
