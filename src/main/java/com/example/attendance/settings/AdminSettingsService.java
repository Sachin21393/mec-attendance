package com.example.attendance.settings;

import com.example.attendance.attendance.AttendanceCalculator;
import com.example.attendance.audit.AuditActorType;
import com.example.attendance.audit.AuditService;
import com.example.attendance.auth.AdminPrincipal;
import com.example.attendance.auth.AdminRole;
import com.example.attendance.common.ForbiddenException;
import com.example.attendance.common.ValidationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AdminSettingsService {

    private final AttendanceSettingsRepository settingsRepository;
    private final WorkCalendarRepository calendarRepository;
    private final AttendanceCalculator calculator;
    private final AuditService auditService;

    public AdminSettingsService(
            AttendanceSettingsRepository settingsRepository,
            WorkCalendarRepository calendarRepository,
            AttendanceCalculator calculator,
            AuditService auditService
    ) {
        this.settingsRepository = settingsRepository;
        this.calendarRepository = calendarRepository;
        this.calculator = calculator;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public SettingsDto getSettings(AdminPrincipal admin) {
        AttendanceSettings s = loadSettings();
        boolean canEditTech = admin != null && admin.role() == AdminRole.TECH_ADMIN;
        return SettingsDto.from(s, canEditTech);
    }

    @Transactional
    public SettingsDto updateSettings(AdminPrincipal admin, UpdateSettingsRequest req) {
        AttendanceSettings s = loadSettings();
        validateTimezone(req.timezoneId());

        if (req.gracePeriodMinutes() < 0 || req.gracePeriodMinutes() > 240) {
            throw new ValidationException("INVALID_GRACE_PERIOD", "Grace period must be between 0 and 240 minutes.");
        }
        if (req.maxProxyPerActorPerDay() < 0 || req.maxProxyPerActorPerDay() > 100) {
            throw new ValidationException("INVALID_PROXY_CAP", "Proxy limit must be between 0 and 100.");
        }
        if (req.geofenceRadiusMeters() != null && (req.geofenceRadiusMeters() < 5 || req.geofenceRadiusMeters() > 50_000)) {
            throw new ValidationException("INVALID_GEOFENCE_RADIUS", "Geofence radius must be between 5 and 50000 meters.");
        }
        if (req.geofenceLatitude() != null && (!Double.isFinite(req.geofenceLatitude()) || req.geofenceLatitude() < -90.0 || req.geofenceLatitude() > 90.0)) {
            throw new ValidationException("INVALID_GEOFENCE_COORDINATES", "Office latitude must be between -90 and 90.");
        }
        if (req.geofenceLongitude() != null && (!Double.isFinite(req.geofenceLongitude()) || req.geofenceLongitude() < -180.0 || req.geofenceLongitude() > 180.0)) {
            throw new ValidationException("INVALID_GEOFENCE_COORDINATES", "Office longitude must be between -180 and 180.");
        }
        if (req.paidLeaveQuota() != null && (req.paidLeaveQuota() < 0 || req.paidLeaveQuota() > 365)) {
            throw new ValidationException("INVALID_LEAVE_QUOTA", "Paid / Sick leave quota must be between 0 and 365.");
        }
        if (req.annualLeaveQuota() != null && (req.annualLeaveQuota() < 0 || req.annualLeaveQuota() > 365)) {
            throw new ValidationException("INVALID_LEAVE_QUOTA", "Annual leave quota must be between 0 and 365.");
        }

        boolean nextGeofenceEnabled = req.geofenceEnabled() != null ? req.geofenceEnabled() : s.isGeofenceEnabled();
        Double nextLat = req.geofenceLatitude() != null ? req.geofenceLatitude() : s.getGeofenceLatitude();
        Double nextLng = req.geofenceLongitude() != null ? req.geofenceLongitude() : s.getGeofenceLongitude();
        if (nextGeofenceEnabled && (nextLat == null || nextLng == null)) {
            throw new ValidationException("GEOFENCE_COORDINATES_REQUIRED", "Office latitude and longitude are required when geofencing is enabled.");
        }

        boolean techChangeRequested = (req.faceMatchThreshold() != null && Math.abs(req.faceMatchThreshold() - s.getFaceMatchThreshold()) > 1e-6)
                || (req.faceModelVersion() != null && !req.faceModelVersion().isBlank() && !req.faceModelVersion().trim().equals(s.getFaceModelVersion()));
        if (techChangeRequested && (admin == null || admin.role() != AdminRole.TECH_ADMIN)) {
            throw new ForbiddenException("TECH_ADMIN_REQUIRED", "Only technical administrators can modify face recognition thresholds or model versions.");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("timezoneId", s.getTimezoneId());
        before.put("shiftStart", s.getDefaultShiftStartLocal().toString());
        before.put("graceMinutes", s.getDefaultGracePeriodMinutes());
        before.put("absenceCutoff", s.getDefaultAbsenceCutoffLocal().toString());
        before.put("proxyEnabled", s.isProxyEnabled());
        before.put("maxProxy", s.getMaxProxyPerActorPerDay());
        before.put("geofenceEnabled", s.isGeofenceEnabled());
        before.put("geofenceRadiusMeters", s.getGeofenceRadiusMeters());

        Instant now = Instant.now();
        s.setWorkplaceName(req.workplaceName().trim());
        s.setTimezoneId(req.timezoneId().trim());
        s.setDefaultShiftStartLocal(req.shiftStartLocal());
        s.setDefaultGracePeriodMinutes(req.gracePeriodMinutes());
        s.setDefaultAbsenceCutoffLocal(req.absenceCutoffLocal());
        if (req.shiftEndLocal() != null) {
            s.setDefaultShiftEndLocal(req.shiftEndLocal());
        }
        s.setProxyEnabled(req.proxyEnabled());
        s.setMaxProxyPerActorPerDay(req.maxProxyPerActorPerDay());
        if (req.proxyCapCountsSuccessfulOnly() != null) {
            s.setProxyCapCountsSuccessfulOnly(req.proxyCapCountsSuccessfulOnly());
        }
        if (req.faceMatchThreshold() != null) {
            if (req.faceMatchThreshold() <= 0.0 || req.faceMatchThreshold() >= 1.0) {
                throw new ValidationException("INVALID_THRESHOLD", "Face match threshold must be between 0.0 and 1.0.");
            }
            s.setFaceMatchThreshold(req.faceMatchThreshold());
        }
        if (req.faceModelVersion() != null && !req.faceModelVersion().isBlank()) {
            s.setFaceModelVersion(req.faceModelVersion().trim());
        }
        if (req.geofenceEnabled() != null) {
            s.setGeofenceEnabled(req.geofenceEnabled());
        }
        if (req.geofenceLatitude() != null) {
            s.setGeofenceLatitude(req.geofenceLatitude());
        }
        if (req.geofenceLongitude() != null) {
            s.setGeofenceLongitude(req.geofenceLongitude());
        }
        if (req.geofenceRadiusMeters() != null) {
            s.setGeofenceRadiusMeters(req.geofenceRadiusMeters());
        }
        if (req.paidLeaveQuota() != null) {
            s.setDefaultPaidLeaveQuota(req.paidLeaveQuota());
        }
        if (req.annualLeaveQuota() != null) {
            s.setDefaultAnnualLeaveQuota(req.annualLeaveQuota());
        }
        if (req.autoMarkLeaveAfterCutoff() != null) {
            boolean wasEnabled = s.isAutoMarkLeaveAfterCutoff();
            s.setAutoMarkLeaveAfterCutoff(req.autoMarkLeaveAfterCutoff());
            if (!wasEnabled && req.autoMarkLeaveAfterCutoff()) {
                s.setAutoLeaveEffectiveFromDate(calculator.resolveAttendanceDate(now, s));
            }
        }
        s.setEffectiveFromUtc(req.effectiveFromUtc() != null ? req.effectiveFromUtc() : now);
        s.setUpdatedByAdminId(admin != null ? admin.adminUserId() : null);
        s.setUpdatedAt(now);
        settingsRepository.save(s);

        // Keep today's and upcoming explicit work_calendar rows synced with updated default shift/grace/logout/cutoff settings
        LocalDate currentWorkplaceDate = calculator.resolveAttendanceDate(now, s);
        List<WorkCalendarDay> upcomingDays = calendarRepository.findByCalendarDateGreaterThanEqual(currentWorkplaceDate);
        if (!upcomingDays.isEmpty()) {
            for (WorkCalendarDay day : upcomingDays) {
                day.setShiftStartLocal(s.getDefaultShiftStartLocal());
                day.setGracePeriodMinutes(s.getDefaultGracePeriodMinutes());
                day.setAbsenceCutoffLocal(s.getDefaultAbsenceCutoffLocal());
                day.setShiftEndLocal(s.getDefaultShiftEndLocal());
                day.setTimezoneId(s.getTimezoneId());
                day.setUpdatedAt(now);
            }
            calendarRepository.saveAll(upcomingDays);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("timezoneId", s.getTimezoneId());
        after.put("shiftStart", s.getDefaultShiftStartLocal().toString());
        after.put("graceMinutes", s.getDefaultGracePeriodMinutes());
        after.put("absenceCutoff", s.getDefaultAbsenceCutoffLocal().toString());
        after.put("proxyEnabled", s.isProxyEnabled());
        after.put("maxProxy", s.getMaxProxyPerActorPerDay());
        after.put("geofenceEnabled", s.isGeofenceEnabled());
        after.put("geofenceRadiusMeters", s.getGeofenceRadiusMeters());
        after.put("paidLeaveQuota", s.getDefaultPaidLeaveQuota());
        after.put("annualLeaveQuota", s.getDefaultAnnualLeaveQuota());
        after.put("autoMarkLeaveAfterCutoff", s.isAutoMarkLeaveAfterCutoff());
        after.put("effectiveFromUtc", s.getEffectiveFromUtc().toString());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("before", before);
        metadata.put("after", after);
        auditService.record("SETTINGS_UPDATED", AuditActorType.ADMIN, admin != null ? admin.adminUserId() : null, null, metadata);

        return SettingsDto.from(s, admin != null && admin.role() == AdminRole.TECH_ADMIN);
    }

    @Transactional(readOnly = true)
    public CalendarMonthDto getCalendarMonth(YearMonth month) {
        AttendanceSettings settings = loadSettings();
        Map<LocalDate, WorkCalendarDay> explicit = calendarRepository
                .findByCalendarDateBetweenOrderByCalendarDateAsc(month.atDay(1), month.atEndOfMonth())
                .stream()
                .collect(Collectors.toMap(WorkCalendarDay::getCalendarDate, Function.identity()));

        List<CalendarDayDto> days = new ArrayList<>(month.lengthOfMonth());
        int workingCount = 0;
        for (int i = 1; i <= month.lengthOfMonth(); i++) {
            LocalDate date = month.atDay(i);
            WorkCalendarDay saved = explicit.get(date);
            AttendanceCalculator.EffectiveDayConfig dc = calculator.resolveDayConfig(date, saved, settings);
            if (dc.dayType() == DayType.WORKING) {
                workingCount++;
            }
            days.add(new CalendarDayDto(
                    date,
                    dc.dayType(),
                    dc.shiftStartLocal(),
                    dc.gracePeriodMinutes(),
                    dc.absenceCutoffLocal(),
                    dc.shiftEndLocal(),
                    dc.timezone().getId(),
                    dc.note(),
                    saved != null
            ));
        }

        return new CalendarMonthDto(month.toString(), settings.getTimezoneId(), workingCount, days);
    }

    @Transactional
    public CalendarMonthDto updateCalendarMonth(AdminPrincipal admin, YearMonth month, UpdateCalendarRequest req) {
        AttendanceSettings settings = loadSettings();
        Instant now = Instant.now();

        Map<LocalDate, WorkCalendarDay> existingMap = calendarRepository
                .findByCalendarDateBetweenOrderByCalendarDateAsc(month.atDay(1), month.atEndOfMonth())
                .stream()
                .collect(Collectors.toMap(WorkCalendarDay::getCalendarDate, Function.identity()));

        if (req.copyFromMonth() != null) {
            applyCopiedPattern(admin, month, req.copyFromMonth(), settings, existingMap, now);
        } else if (req.weeklyOffDays() != null) {
            Set<DayOfWeek> offDays = EnumSet.noneOf(DayOfWeek.class);
            offDays.addAll(req.weeklyOffDays());
            for (int d = 1; d <= month.lengthOfMonth(); d++) {
                LocalDate date = month.atDay(d);
                DayType type = offDays.contains(date.getDayOfWeek()) ? DayType.WEEKLY_OFF : DayType.WORKING;
                upsertDay(existingMap, date, type, settings.getDefaultShiftStartLocal(),
                        settings.getDefaultGracePeriodMinutes(), settings.getDefaultAbsenceCutoffLocal(),
                        settings.getDefaultShiftEndLocal(), settings.getTimezoneId(),
                        type == DayType.WEEKLY_OFF ? "Weekly Off" : null,
                        admin != null ? admin.adminUserId() : null, now);
            }
        }

        if (req.days() != null) {
            for (CalendarDayUpdate item : req.days()) {
                if (!YearMonth.from(item.date()).equals(month)) {
                    throw new ValidationException("DATE_OUT_OF_MONTH", "Date " + item.date() + " does not belong to " + month);
                }
                LocalTime shiftStart = item.shiftStartLocal() != null ? item.shiftStartLocal() : settings.getDefaultShiftStartLocal();
                int grace = item.gracePeriodMinutes() != null ? item.gracePeriodMinutes() : settings.getDefaultGracePeriodMinutes();
                LocalTime cutoff = item.absenceCutoffLocal() != null ? item.absenceCutoffLocal() : settings.getDefaultAbsenceCutoffLocal();
                LocalTime shiftEnd = item.shiftEndLocal() != null ? item.shiftEndLocal() : settings.getDefaultShiftEndLocal();
                upsertDay(existingMap, item.date(), item.dayType(), shiftStart, grace, cutoff, shiftEnd,
                        settings.getTimezoneId(), item.note(), admin != null ? admin.adminUserId() : null, now);
            }
        }

        calendarRepository.saveAll(existingMap.values());
        CalendarMonthDto updated = getCalendarMonth(month);

        auditService.record(
                "CALENDAR_UPDATED",
                AuditActorType.ADMIN,
                admin != null ? admin.adminUserId() : null,
                null,
                Map.of(
                        "month", month.toString(),
                        "workingDaysCount", updated.workingDaysCount(),
                        "copiedFrom", req.copyFromMonth() != null ? req.copyFromMonth().toString() : ""
                )
        );
        return updated;
    }

    private void applyCopiedPattern(
            AdminPrincipal admin,
            YearMonth targetMonth,
            YearMonth sourceMonth,
            AttendanceSettings settings,
            Map<LocalDate, WorkCalendarDay> targetMap,
            Instant now
    ) {
        CalendarMonthDto src = getCalendarMonth(sourceMonth);
        Map<DayOfWeek, DayType> weekdayPattern = new LinkedHashMap<>();
        for (CalendarDayDto d : src.days()) {
            if (d.dayType() == DayType.WEEKLY_OFF) {
                weekdayPattern.put(d.date().getDayOfWeek(), DayType.WEEKLY_OFF);
            }
        }
        for (int i = 1; i <= targetMonth.lengthOfMonth(); i++) {
            LocalDate date = targetMonth.atDay(i);
            DayType type = weekdayPattern.getOrDefault(date.getDayOfWeek(), DayType.WORKING);
            upsertDay(targetMap, date, type, settings.getDefaultShiftStartLocal(),
                    settings.getDefaultGracePeriodMinutes(), settings.getDefaultAbsenceCutoffLocal(),
                    settings.getDefaultShiftEndLocal(), settings.getTimezoneId(),
                    type == DayType.WEEKLY_OFF ? "Weekly Off (Copied)" : null,
                    admin != null ? admin.adminUserId() : null, now);
        }
    }

    private void upsertDay(
            Map<LocalDate, WorkCalendarDay> existingMap,
            LocalDate date,
            DayType dayType,
            LocalTime shiftStart,
            int graceMinutes,
            LocalTime cutoff,
            LocalTime shiftEnd,
            String timezoneId,
            String note,
            UUID adminId,
            Instant now
    ) {
        WorkCalendarDay entity = existingMap.get(date);
        if (entity == null) {
            entity = new WorkCalendarDay(UUID.randomUUID(), date, dayType, shiftStart, graceMinutes, cutoff, shiftEnd, timezoneId, note, adminId, now);
            existingMap.put(date, entity);
        } else {
            entity.setDayType(dayType);
            entity.setShiftStartLocal(shiftStart);
            entity.setGracePeriodMinutes(graceMinutes);
            entity.setAbsenceCutoffLocal(cutoff);
            entity.setShiftEndLocal(shiftEnd);
            entity.setTimezoneId(timezoneId);
            entity.setNote(note);
            entity.setUpdatedAt(now);
        }
    }

    private AttendanceSettings loadSettings() {
        return settingsRepository.findTopByOrderByEffectiveFromUtcDesc()
                .orElseThrow(() -> new IllegalStateException("Attendance settings not found."));
    }

    private static void validateTimezone(String timezoneId) {
        try {
            ZoneId.of(timezoneId.trim());
        } catch (ZoneRulesException | NullPointerException e) {
            throw new ValidationException("INVALID_TIMEZONE", "Unknown IANA timezone ID: " + timezoneId);
        }
    }

    public record SettingsDto(
            UUID id,
            String workplaceName,
            String timezoneId,
            LocalTime shiftStartLocal,
            int gracePeriodMinutes,
            LocalTime absenceCutoffLocal,
            LocalTime shiftEndLocal,
            boolean proxyEnabled,
            int maxProxyPerActorPerDay,
            boolean proxyCapCountsSuccessfulOnly,
            double faceMatchThreshold,
            String faceModelVersion,
            boolean canEditBiometricConfig,
            boolean geofenceEnabled,
            Double geofenceLatitude,
            Double geofenceLongitude,
            int geofenceRadiusMeters,
            int paidLeaveQuota,
            int annualLeaveQuota,
            boolean autoMarkLeaveAfterCutoff,
            Instant effectiveFromUtc,
            Instant updatedAt
    ) {
        public static SettingsDto from(AttendanceSettings s, boolean canEditTech) {
            return new SettingsDto(
                    s.getId(),
                    s.getWorkplaceName(),
                    s.getTimezoneId(),
                    s.getDefaultShiftStartLocal(),
                    s.getDefaultGracePeriodMinutes(),
                    s.getDefaultAbsenceCutoffLocal(),
                    s.getDefaultShiftEndLocal(),
                    s.isProxyEnabled(),
                    s.getMaxProxyPerActorPerDay(),
                    s.isProxyCapCountsSuccessfulOnly(),
                    s.getFaceMatchThreshold(),
                    s.getFaceModelVersion(),
                    canEditTech,
                    s.isGeofenceEnabled(),
                    s.getGeofenceLatitude(),
                    s.getGeofenceLongitude(),
                    s.getGeofenceRadiusMeters(),
                    s.getDefaultPaidLeaveQuota(),
                    s.getDefaultAnnualLeaveQuota(),
                    s.isAutoMarkLeaveAfterCutoff(),
                    s.getEffectiveFromUtc(),
                    s.getUpdatedAt()
            );
        }
    }

    public record UpdateSettingsRequest(
            @NotBlank(message = "Workplace name is required") String workplaceName,
            @NotBlank(message = "Timezone is required") String timezoneId,
            @NotNull(message = "Shift start time is required") LocalTime shiftStartLocal,
            int gracePeriodMinutes,
            @NotNull(message = "Absence cutoff time is required") LocalTime absenceCutoffLocal,
            LocalTime shiftEndLocal,
            boolean proxyEnabled,
            int maxProxyPerActorPerDay,
            Boolean proxyCapCountsSuccessfulOnly,
            Double faceMatchThreshold,
            String faceModelVersion,
            Instant effectiveFromUtc,
            Boolean geofenceEnabled,
            Double geofenceLatitude,
            Double geofenceLongitude,
            Integer geofenceRadiusMeters,
            Integer paidLeaveQuota,
            Integer annualLeaveQuota,
            Boolean autoMarkLeaveAfterCutoff
    ) {
    }

    public record CalendarDayDto(
            LocalDate date,
            DayType dayType,
            LocalTime shiftStartLocal,
            int gracePeriodMinutes,
            LocalTime absenceCutoffLocal,
            LocalTime shiftEndLocal,
            String timezoneId,
            String note,
            boolean configured
    ) {
    }

    public record CalendarMonthDto(
            String month,
            String timezoneId,
            int workingDaysCount,
            List<CalendarDayDto> days
    ) {
    }

    public record CalendarDayUpdate(
            LocalDate date,
            DayType dayType,
            LocalTime shiftStartLocal,
            Integer gracePeriodMinutes,
            LocalTime absenceCutoffLocal,
            LocalTime shiftEndLocal,
            String note
    ) {
    }

    public record UpdateCalendarRequest(
            List<CalendarDayUpdate> days,
            List<DayOfWeek> weeklyOffDays,
            YearMonth copyFromMonth
    ) {
    }
}
