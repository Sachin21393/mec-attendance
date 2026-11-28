package com.example.attendance.attendance;

import com.example.attendance.audit.AuditEvent;
import com.example.attendance.audit.AuditEventRepository;
import com.example.attendance.common.ResourceNotFoundException;
import com.example.attendance.leaves.LeaveRecord;
import com.example.attendance.leaves.LeaveRecordRepository;
import com.example.attendance.leaves.LeaveService;
import com.example.attendance.leaves.LeaveSource;
import com.example.attendance.leaves.LeaveType;
import com.example.attendance.settings.AttendanceSettings;
import com.example.attendance.settings.AttendanceSettingsRepository;
import com.example.attendance.settings.DayType;
import com.example.attendance.settings.WorkCalendarDay;
import com.example.attendance.settings.WorkCalendarRepository;
import com.example.attendance.workers.Worker;
import com.example.attendance.workers.WorkerRepository;
import com.example.attendance.workers.WorkerStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MonthlyAttendanceQueryService {

    private final WorkerRepository workerRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final AttendanceCorrectionRepository correctionRepository;
    private final LeaveRecordRepository leaveRepository;
    private final LeaveService leaveService;
    private final WorkCalendarRepository calendarRepository;
    private final AttendanceSettingsRepository settingsRepository;
    private final AuditEventRepository auditEventRepository;
    private final AttendanceCalculator calculator;
    private final Clock clock;

    public MonthlyAttendanceQueryService(
            WorkerRepository workerRepository,
            AttendanceRecordRepository attendanceRepository,
            AttendanceCorrectionRepository correctionRepository,
            LeaveRecordRepository leaveRepository,
            LeaveService leaveService,
            WorkCalendarRepository calendarRepository,
            AttendanceSettingsRepository settingsRepository,
            AuditEventRepository auditEventRepository,
            AttendanceCalculator calculator,
            Clock clock
    ) {
        this.workerRepository = workerRepository;
        this.attendanceRepository = attendanceRepository;
        this.correctionRepository = correctionRepository;
        this.leaveRepository = leaveRepository;
        this.leaveService = leaveService;
        this.calendarRepository = calendarRepository;
        this.settingsRepository = settingsRepository;
        this.auditEventRepository = auditEventRepository;
        this.calculator = calculator;
        this.clock = clock;
    }

    @Transactional
    public DashboardSummary getDashboard(YearMonth month) {
        leaveService.applyAutoCutoffLeavesForMonth(month);
        AttendanceSettings settings = loadSettings();
        Instant now = clock.instant();
        ZoneId zone = calculator.resolveZone(settings);
        LocalDate workplaceToday = calculator.resolveAttendanceDate(now, settings);

        List<AttendanceCalculator.EffectiveDayConfig> dayConfigs = buildMonthDayConfigs(month, settings);
        int configuredWorkingDays = (int) dayConfigs.stream()
                .filter(d -> d.dayType() == DayType.WORKING)
                .count();

        List<Worker> activeWorkers = workerRepository.findAll(Sort.by("workerCode").ascending()).stream()
                .filter(w -> w.getStatus() != WorkerStatus.DISABLED)
                .toList();

        Map<LocalDate, Map<UUID, AttendanceRecord>> recordsByDateAndWorker = loadRecordsMatrix(month);
        Map<LocalDate, Map<UUID, LeaveRecord>> leavesByDateAndWorker = loadLeavesMatrix(month);

        int presentToday = 0;
        int onTimeToday = 0;
        int lateToday = 0;
        int absentToday = 0;
        int leavesToday = 0;
        int notDueToday = 0;

        WorkCalendarDay todayCal = calendarRepository.findByCalendarDate(workplaceToday).orElse(null);
        AttendanceCalculator.EffectiveDayConfig todayConfig = calculator.resolveDayConfig(workplaceToday, todayCal, settings);
        Map<UUID, AttendanceRecord> todayRecords = attendanceRepository
                .findByAttendanceDateBetween(workplaceToday, workplaceToday)
                .stream()
                .collect(Collectors.toMap(AttendanceRecord::getSubjectWorkerId, Function.identity(), (a, b) -> a));
        Map<UUID, LeaveRecord> todayLeaves = leaveRepository
                .findByLeaveDateBetween(workplaceToday, workplaceToday)
                .stream()
                .collect(Collectors.toMap(LeaveRecord::getWorkerId, Function.identity(), (a, b) -> a));

        for (Worker w : activeWorkers) {
            if (!isEmployedOn(w, workplaceToday)) {
                continue;
            }
            AttendanceRecord rec = todayRecords.get(w.getId());
            LeaveRecord leaveRec = todayLeaves.get(w.getId());
            AttendanceCalculator.DerivedGridState state = calculator.deriveDayState(todayConfig, rec, leaveRec, now);
            switch (state) {
                case ON_TIME -> {
                    presentToday++;
                    onTimeToday++;
                }
                case LATE -> {
                    presentToday++;
                    lateToday++;
                }
                case ABSENT -> absentToday++;
                case LEAVE -> leavesToday++;
                case NOT_DUE -> notDueToday++;
                default -> {
                }
            }
        }

        int monthlyOnTime = 0;
        int monthlyLate = 0;
        int monthlyAbsent = 0;
        int monthlyLeaves = 0;
        int monthlyNotDue = 0;
        for (Worker w : activeWorkers) {
            for (AttendanceCalculator.EffectiveDayConfig dc : dayConfigs) {
                if (!isEmployedOn(w, dc.date())) {
                    continue;
                }
                AttendanceRecord rec = recordsByDateAndWorker
                        .getOrDefault(dc.date(), Map.of())
                        .get(w.getId());
                LeaveRecord leaveRec = leavesByDateAndWorker
                        .getOrDefault(dc.date(), Map.of())
                        .get(w.getId());
                AttendanceCalculator.DerivedGridState state = calculator.deriveDayState(dc, rec, leaveRec, now);
                switch (state) {
                    case ON_TIME -> monthlyOnTime++;
                    case LATE -> monthlyLate++;
                    case ABSENT -> monthlyAbsent++;
                    case LEAVE -> monthlyLeaves++;
                    case NOT_DUE -> monthlyNotDue++;
                    default -> {
                    }
                }
            }
        }

        Map<UUID, int[]> allLeaveCounts = leaveService.loadAllLeaveCountsByWorker();
        int paidQuota = settings.getDefaultPaidLeaveQuota();
        int annualQuota = settings.getDefaultAnnualLeaveQuota();
        int totalApprovedLeaves = 0;
        int totalPendingLeaves = 0;
        for (Worker w : activeWorkers) {
            int[] counts = allLeaveCounts.getOrDefault(w.getId(), new int[2]);
            int paidTaken = counts[0];
            int annualTaken = counts[1];
            totalApprovedLeaves += (paidTaken + annualTaken);
            totalPendingLeaves += Math.max(0, paidQuota - paidTaken) + Math.max(0, annualQuota - annualTaken);
        }

        return new DashboardSummary(
                month.toString(),
                settings.getWorkplaceName(),
                zone.getId(),
                workplaceToday,
                configuredWorkingDays,
                activeWorkers.size(),
                presentToday,
                onTimeToday,
                lateToday,
                absentToday,
                leavesToday,
                notDueToday,
                monthlyOnTime,
                monthlyLate,
                monthlyAbsent,
                monthlyLeaves,
                monthlyNotDue,
                totalApprovedLeaves,
                totalPendingLeaves,
                paidQuota,
                annualQuota
        );
    }

    @Transactional
    public MonthlyGridResponse getMonthlyGrid(YearMonth month, String query, String team, int page, int size) {
        leaveService.applyAutoCutoffLeavesForMonth(month);
        AttendanceSettings settings = loadSettings();
        Instant now = clock.instant();
        ZoneId zone = calculator.resolveZone(settings);

        List<AttendanceCalculator.EffectiveDayConfig> dayConfigs = buildMonthDayConfigs(month, settings);
        int configuredWorkingDays = (int) dayConfigs.stream()
                .filter(d -> d.dayType() == DayType.WORKING)
                .count();

        List<Worker> allWorkers = workerRepository.findAll(Sort.by("workerCode").ascending());
        String q = query == null ? "" : query.trim().toLowerCase();
        String t = team == null ? "" : team.trim().toLowerCase();

        List<Worker> filtered = allWorkers.stream()
                .filter(w -> q.isEmpty()
                        || w.getFullName().toLowerCase().contains(q)
                        || w.getWorkerCode().toLowerCase().contains(q))
                .filter(w -> t.isEmpty()
                        || (w.getTeamName() != null && w.getTeamName().toLowerCase().equals(t)))
                .toList();

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int fromIdx = Math.min(safePage * safeSize, filtered.size());
        int toIdx = Math.min(fromIdx + safeSize, filtered.size());
        Page<Worker> workerPage = new PageImpl<>(filtered.subList(fromIdx, toIdx), PageRequest.of(safePage, safeSize), filtered.size());

        Map<UUID, Worker> workersById = allWorkers.stream()
                .collect(Collectors.toMap(Worker::getId, Function.identity()));
        Map<LocalDate, Map<UUID, AttendanceRecord>> recordsMatrix = loadRecordsMatrix(month);
        Map<LocalDate, Map<UUID, LeaveRecord>> leavesMatrix = loadLeavesMatrix(month);
        Map<UUID, int[]> allLeaveCounts = leaveService.loadAllLeaveCountsByWorker();

        List<GridDayHeader> days = dayConfigs.stream()
                .map(dc -> new GridDayHeader(dc.date(), dc.dayType(), dc.note()))
                .toList();

        List<WorkerGridRow> rows = new ArrayList<>();
        for (Worker w : workerPage.getContent()) {
            rows.add(buildWorkerRow(w, dayConfigs, recordsMatrix, leavesMatrix, allLeaveCounts, settings, workersById, zone, now));
        }

        List<String> teams = allWorkers.stream()
                .map(Worker::getTeamName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();

        return new MonthlyGridResponse(
                month.toString(),
                settings.getWorkplaceName(),
                zone.getId(),
                configuredWorkingDays,
                teams,
                days,
                rows,
                workerPage.getNumber(),
                workerPage.getSize(),
                workerPage.getTotalElements(),
                workerPage.getTotalPages()
        );
    }

    @Transactional
    public List<WorkerGridRow> getAllWorkerRowsForExport(YearMonth month, String query, String team) {
        MonthlyGridResponse full = getMonthlyGrid(month, query, team, 0, Integer.MAX_VALUE);
        return full.rows();
    }

    @Transactional(readOnly = true)
    public AttendanceDetailDto getRecordDetail(UUID recordId) {
        AttendanceRecord rec = attendanceRepository.findById(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("ATTENDANCE_NOT_FOUND", "Attendance record not found."));
        AttendanceSettings settings = loadSettings();
        ZoneId zone = calculator.resolveZone(settings);

        Worker subject = workerRepository.findById(rec.getSubjectWorkerId()).orElseThrow();
        Worker actor = rec.getActorWorkerId() == null ? null : workerRepository.findById(rec.getActorWorkerId()).orElse(null);
        List<AttendanceCorrection> corrections = correctionRepository.findByAttendanceRecordIdOrderByCreatedAtDesc(recordId);
        List<AuditEvent> recentAudits = auditEventRepository.findAllByOrderByEventTimestampUtcDesc(PageRequest.of(0, 50))
                .stream()
                .filter(e -> subject.getId().equals(e.getSubjectWorkerId()))
                .limit(10)
                .toList();

        return new AttendanceDetailDto(
                rec.getId(),
                subject.getId(),
                subject.getWorkerCode(),
                subject.getFullName(),
                subject.getTeamName(),
                actor != null ? actor.getId() : null,
                actor != null ? actor.getWorkerCode() : null,
                actor != null ? actor.getFullName() : "Admin",
                rec.getAttendanceDate(),
                rec.getCheckInAtUtc(),
                calculator.formatLocalTime(rec.getCheckInAtUtc(), zone),
                zone.getId(),
                rec.getAttendanceStatus(),
                rec.getAttendanceMethod(),
                rec.getNotes(),
                rec.getCheckOutAtUtc(),
                rec.getCheckOutAtUtc() != null ? calculator.formatLocalTime(rec.getCheckOutAtUtc(), zone) : null,
                rec.getLogoutStatus(),
                rec.getLogoutStatus() != null ? rec.getLogoutStatus().getDisplayLabel() : null,
                corrections.stream().map(c -> new CorrectionSummary(
                        c.getId(), c.getPreviousStatus(), c.getNewStatus(), c.getReason(), c.getCreatedAt()
                )).toList(),
                recentAudits.stream().map(a -> new AuditSnippet(
                        a.getId(), a.getEventType(), a.getActorType().name(), a.getEventTimestampUtc(), a.getMetadataJson()
                )).toList()
        );
    }

    public WorkerGridRow buildWorkerRow(
            Worker worker,
            List<AttendanceCalculator.EffectiveDayConfig> dayConfigs,
            Map<LocalDate, Map<UUID, AttendanceRecord>> recordsMatrix,
            Map<LocalDate, Map<UUID, LeaveRecord>> leavesMatrix,
            Map<UUID, int[]> allLeaveCounts,
            AttendanceSettings settings,
            Map<UUID, Worker> workersById,
            ZoneId zone,
            Instant now
    ) {
        List<GridCell> cells = new ArrayList<>(dayConfigs.size());
        int applicableWorkingDays = 0;
        int workedDays = 0;
        int onTimeDays = 0;
        int lateDays = 0;
        int absentDays = 0;
        int leaveDays = 0;
        int holidayDays = 0;
        int weeklyOffDays = 0;
        int earlyLogoutDays = 0;
        int extendedWorkHoursDays = 0;

        for (AttendanceCalculator.EffectiveDayConfig dc : dayConfigs) {
            AttendanceRecord rec = recordsMatrix.getOrDefault(dc.date(), Map.of()).get(worker.getId());
            LeaveRecord leaveRec = leavesMatrix.getOrDefault(dc.date(), Map.of()).get(worker.getId());
            if (!isEmployedOn(worker, dc.date())) {
                cells.add(new GridCell(dc.date(), "NOT_EMPLOYED", null, null, null, null, null, null, null, null, null, null, null, null));
                continue;
            }
            if (dc.dayType() == DayType.HOLIDAY) {
                holidayDays++;
            } else if (dc.dayType() == DayType.WEEKLY_OFF) {
                weeklyOffDays++;
            } else {
                applicableWorkingDays++;
            }

            AttendanceCalculator.DerivedGridState state = calculator.deriveDayState(dc, rec, leaveRec, now);
            if (dc.dayType() == DayType.WORKING && rec != null) {
                workedDays++;
                if (state == AttendanceCalculator.DerivedGridState.ON_TIME) {
                    onTimeDays++;
                } else if (state == AttendanceCalculator.DerivedGridState.LATE) {
                    lateDays++;
                }
                if (rec.getLogoutStatus() == LogoutStatus.EARLY_LOGOUT) {
                    earlyLogoutDays++;
                } else if (rec.getLogoutStatus() == LogoutStatus.EXTENDED_WORK_HOURS) {
                    extendedWorkHoursDays++;
                }
            } else if (state == AttendanceCalculator.DerivedGridState.LEAVE) {
                leaveDays++;
            } else if (state == AttendanceCalculator.DerivedGridState.ABSENT) {
                absentDays++;
            }

            Worker actor = (rec != null && rec.getActorWorkerId() != null) ? workersById.get(rec.getActorWorkerId()) : null;
            cells.add(new GridCell(
                    dc.date(),
                    state.name(),
                    rec != null ? rec.getId() : null,
                    rec != null ? calculator.formatLocalTime(rec.getCheckInAtUtc(), zone) : null,
                    rec != null ? rec.getAttendanceMethod() : null,
                    actor != null ? actor.getWorkerCode() : null,
                    actor != null ? actor.getFullName() : null,
                    (rec != null && rec.getCheckOutAtUtc() != null) ? calculator.formatLocalTime(rec.getCheckOutAtUtc(), zone) : null,
                    rec != null ? rec.getLogoutStatus() : null,
                    (rec != null && rec.getLogoutStatus() != null) ? rec.getLogoutStatus().getDisplayLabel() : null,
                    leaveRec != null ? leaveRec.getLeaveType() : null,
                    leaveRec != null ? leaveRec.getLeaveType().getDisplayLabel() : null,
                    leaveRec != null ? leaveRec.getSource() : null,
                    leaveRec != null ? leaveRec.getSource().getDisplayLabel() : null
            ));
        }

        int[] counts = allLeaveCounts.getOrDefault(worker.getId(), new int[2]);
        int paidTaken = counts[0];
        int annualTaken = counts[1];
        int paidPending = Math.max(0, settings.getDefaultPaidLeaveQuota() - paidTaken);
        int annualPending = Math.max(0, settings.getDefaultAnnualLeaveQuota() - annualTaken);

        return new WorkerGridRow(
                worker.getId(),
                worker.getWorkerCode(),
                worker.getFullName(),
                worker.getTeamName(),
                worker.getStatus(),
                applicableWorkingDays,
                workedDays,
                onTimeDays,
                lateDays,
                absentDays,
                leaveDays,
                holidayDays,
                weeklyOffDays,
                earlyLogoutDays,
                extendedWorkHoursDays,
                paidTaken,
                paidPending,
                annualTaken,
                annualPending,
                paidTaken + annualTaken,
                paidPending + annualPending,
                cells
        );
    }

    public List<AttendanceCalculator.EffectiveDayConfig> buildMonthDayConfigs(YearMonth month, AttendanceSettings settings) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        Map<LocalDate, WorkCalendarDay> calMap = calendarRepository.findByCalendarDateBetweenOrderByCalendarDateAsc(start, end)
                .stream()
                .collect(Collectors.toMap(WorkCalendarDay::getCalendarDate, Function.identity()));

        List<AttendanceCalculator.EffectiveDayConfig> result = new ArrayList<>(month.lengthOfMonth());
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            result.add(calculator.resolveDayConfig(date, calMap.get(date), settings));
        }
        return result;
    }

    private Map<LocalDate, Map<UUID, AttendanceRecord>> loadRecordsMatrix(YearMonth month) {
        List<AttendanceRecord> recs = attendanceRepository.findByAttendanceDateBetween(month.atDay(1), month.atEndOfMonth());
        Map<LocalDate, Map<UUID, AttendanceRecord>> matrix = new HashMap<>();
        for (AttendanceRecord rec : recs) {
            matrix.computeIfAbsent(rec.getAttendanceDate(), d -> new HashMap<>())
                    .put(rec.getSubjectWorkerId(), rec);
        }
        return matrix;
    }

    private Map<LocalDate, Map<UUID, LeaveRecord>> loadLeavesMatrix(YearMonth month) {
        List<LeaveRecord> leaves = leaveRepository.findByLeaveDateBetween(month.atDay(1), month.atEndOfMonth());
        Map<LocalDate, Map<UUID, LeaveRecord>> matrix = new HashMap<>();
        for (LeaveRecord l : leaves) {
            matrix.computeIfAbsent(l.getLeaveDate(), d -> new HashMap<>())
                    .put(l.getWorkerId(), l);
        }
        return matrix;
    }

    private static boolean isEmployedOn(Worker worker, LocalDate date) {
        if (worker.getEmploymentStartDate() != null && date.isBefore(worker.getEmploymentStartDate())) {
            return false;
        }
        return worker.getEmploymentEndDate() == null || !date.isAfter(worker.getEmploymentEndDate());
    }

    private AttendanceSettings loadSettings() {
        return settingsRepository.findTopByOrderByEffectiveFromUtcDesc()
                .orElseThrow(() -> new IllegalStateException("Attendance settings not found."));
    }

    public record DashboardSummary(
            String month,
            String workplaceName,
            String timezoneId,
            LocalDate todayDate,
            int configuredWorkingDays,
            int activeWorkersCount,
            int presentTodayCount,
            int onTimeTodayCount,
            int lateTodayCount,
            int absentTodayCount,
            int leavesTodayCount,
            int notDueTodayCount,
            int monthlyOnTimeCount,
            int monthlyLateCount,
            int monthlyAbsentCount,
            int monthlyLeavesCount,
            int monthlyNotDueCount,
            int totalApprovedLeavesCount,
            int totalPendingLeavesCount,
            int paidLeaveQuota,
            int annualLeaveQuota
    ) {
    }

    public record GridDayHeader(LocalDate date, DayType dayType, String note) {
    }

    public record GridCell(
            LocalDate date,
            String state,
            UUID recordId,
            String checkInLocalTime,
            AttendanceMethod method,
            String actorWorkerCode,
            String actorWorkerName,
            String checkOutLocalTime,
            LogoutStatus logoutStatus,
            String logoutStatusLabel,
            LeaveType leaveType,
            String leaveTypeLabel,
            LeaveSource leaveSource,
            String leaveSourceLabel
    ) {
    }

    public record WorkerGridRow(
            UUID workerId,
            String workerCode,
            String fullName,
            String teamName,
            WorkerStatus status,
            int applicableWorkingDays,
            int workedDays,
            int onTimeDays,
            int lateDays,
            int absentDays,
            int leaveDays,
            int holidayDays,
            int weeklyOffDays,
            int earlyLogoutDays,
            int extendedWorkHoursDays,
            int paidLeaveTaken,
            int paidLeavePending,
            int annualLeaveTaken,
            int annualLeavePending,
            int totalLeaveTaken,
            int totalLeavePending,
            List<GridCell> cells
    ) {
    }

    public record MonthlyGridResponse(
            String month,
            String workplaceName,
            String timezoneId,
            int configuredWorkingDays,
            List<String> teams,
            List<GridDayHeader> days,
            List<WorkerGridRow> rows,
            int page,
            int size,
            long totalWorkers,
            int totalPages
    ) {
    }

    public record AttendanceDetailDto(
            UUID id,
            UUID subjectWorkerId,
            String subjectWorkerCode,
            String subjectWorkerName,
            String subjectTeamName,
            UUID actorWorkerId,
            String actorWorkerCode,
            String actorWorkerName,
            LocalDate attendanceDate,
            Instant checkInAtUtc,
            String checkInLocalTime,
            String timezoneId,
            AttendanceStatus attendanceStatus,
            AttendanceMethod attendanceMethod,
            String notes,
            Instant checkOutAtUtc,
            String checkOutLocalTime,
            LogoutStatus logoutStatus,
            String logoutStatusLabel,
            List<CorrectionSummary> corrections,
            List<AuditSnippet> recentAudits
    ) {
    }

    public record CorrectionSummary(
            UUID id,
            String previousStatus,
            String newStatus,
            String reason,
            Instant createdAt
    ) {
    }

    public record AuditSnippet(
            UUID id,
            String eventType,
            String actorType,
            Instant eventTimestampUtc,
            String metadataJson
    ) {
    }
}
