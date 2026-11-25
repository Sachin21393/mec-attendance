package com.example.attendance.reports;

import com.example.attendance.attendance.AttendanceMethod;
import com.example.attendance.attendance.MonthlyAttendanceQueryService;
import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.AdminPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AdminReportsService {

    private final MonthlyAttendanceQueryService queryService;
    private final AuditService auditService;

    public AdminReportsService(MonthlyAttendanceQueryService queryService, AuditService auditService) {
        this.queryService = queryService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ReportPreviewResponse preview(YearMonth month, String query, String team) {
        MonthlyAttendanceQueryService.MonthlyGridResponse grid = queryService.getMonthlyGrid(month, query, team, 0, Integer.MAX_VALUE);
        List<WorkerReportSummary> workers = grid.rows().stream()
                .map(r -> new WorkerReportSummary(
                        r.workerCode(),
                        r.fullName(),
                        r.teamName(),
                        r.status().name(),
                        grid.configuredWorkingDays(),
                        r.applicableWorkingDays(),
                        r.workedDays(),
                        r.onTimeDays(),
                        r.lateDays(),
                        r.absentDays(),
                        r.holidayDays(),
                        r.weeklyOffDays()
                ))
                .toList();
        return new ReportPreviewResponse(
                grid.month(),
                grid.workplaceName(),
                grid.timezoneId(),
                grid.configuredWorkingDays(),
                grid.teams(),
                workers
        );
    }

    @Transactional
    public byte[] generateCsv(AdminPrincipal admin, YearMonth month, String query, String team) {
        MonthlyAttendanceQueryService.MonthlyGridResponse grid = queryService.getMonthlyGrid(month, query, team, 0, Integer.MAX_VALUE);

        StringBuilder csv = new StringBuilder();
        List<String> headers = new ArrayList<>();
        headers.add("Worker Code");
        headers.add("Worker Name");
        headers.add("Team");
        headers.add("Status");
        headers.add("Configured Working Days");
        headers.add("Applicable Working Days");
        headers.add("Worked Days");
        headers.add("On-Time Days");
        headers.add("Late Days");
        headers.add("Absent Days");
        headers.add("Holiday Days");
        headers.add("Weekly Off Days");

        for (MonthlyAttendanceQueryService.GridDayHeader d : grid.days()) {
            String ds = d.date().toString();
            headers.add(ds + " Status");
            headers.add(ds + " Time");
            headers.add(ds + " Method");
            headers.add(ds + " Proxy Actor");
        }
        csv.append(CsvExportUtil.toCsvRow(headers));

        for (MonthlyAttendanceQueryService.WorkerGridRow row : grid.rows()) {
            List<String> cells = new ArrayList<>(headers.size());
            cells.add(row.workerCode());
            cells.add(row.fullName());
            cells.add(row.teamName() != null ? row.teamName() : "");
            cells.add(row.status().name());
            cells.add(String.valueOf(grid.configuredWorkingDays()));
            cells.add(String.valueOf(row.applicableWorkingDays()));
            cells.add(String.valueOf(row.workedDays()));
            cells.add(String.valueOf(row.onTimeDays()));
            cells.add(String.valueOf(row.lateDays()));
            cells.add(String.valueOf(row.absentDays()));
            cells.add(String.valueOf(row.holidayDays()));
            cells.add(String.valueOf(row.weeklyOffDays()));

            for (MonthlyAttendanceQueryService.GridCell cell : row.cells()) {
                cells.add(cell.state());
                cells.add(cell.checkInLocalTime() != null ? cell.checkInLocalTime() : "");
                cells.add(cell.method() != null ? cell.method().name() : "");
                if (cell.method() == AttendanceMethod.PROXY && cell.actorWorkerCode() != null) {
                    cells.add(cell.actorWorkerName() + " (" + cell.actorWorkerCode() + ")");
                } else {
                    cells.add("");
                }
            }
            csv.append(CsvExportUtil.toCsvRow(cells));
        }

        auditService.record(
                "ATTENDANCE_CSV_EXPORTED",
                AuditActorType.ADMIN,
                admin != null ? admin.adminUserId() : null,
                null,
                Map.of(
                        "month", month.toString(),
                        "rowCount", grid.rows().size(),
                        "query", query == null ? "" : query,
                        "team", team == null ? "" : team
                )
        );

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public record WorkerReportSummary(
            String workerCode,
            String fullName,
            String teamName,
            String status,
            int configuredWorkingDays,
            int applicableWorkingDays,
            int workedDays,
            int onTimeDays,
            int lateDays,
            int absentDays,
            int holidayDays,
            int weeklyOffDays
    ) {
    }

    public record ReportPreviewResponse(
            String month,
            String workplaceName,
            String timezoneId,
            int configuredWorkingDays,
            List<String> teams,
            List<WorkerReportSummary> workers
    ) {
    }
}
