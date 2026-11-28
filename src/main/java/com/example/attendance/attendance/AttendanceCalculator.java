package com.example.attendance.attendance;

import com.example.attendance.leaves.LeaveRecord;
import com.example.attendance.settings.AttendanceSettings;
import com.example.attendance.settings.DayType;
import com.example.attendance.settings.WorkCalendarDay;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class AttendanceCalculator {

    private static final DateTimeFormatter LOCAL_TIME_DISPLAY = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    public ZoneId resolveZone(AttendanceSettings settings) {
        return ZoneId.of(settings.getTimezoneId());
    }

    /**
     * Resolves the workplace attendance calendar date for a UTC timestamp.
     * Supports overnight shifts: if absenceCutoffLocal < shiftStartLocal and current local time
     * is before absenceCutoffLocal, the attendance belongs to the previous calendar date's shift.
     */
    public LocalDate resolveAttendanceDate(Instant utcInstant, AttendanceSettings settings) {
        ZoneId zone = resolveZone(settings);
        ZonedDateTime zdt = utcInstant.atZone(zone);
        LocalTime localTime = zdt.toLocalTime();
        LocalTime shiftStart = settings.getDefaultShiftStartLocal();
        LocalTime cutoff = settings.getDefaultAbsenceCutoffLocal();
        if (cutoff.isBefore(shiftStart) && localTime.isBefore(cutoff)) {
            return zdt.toLocalDate().minusDays(1);
        }
        return zdt.toLocalDate();
    }

    public EffectiveDayConfig resolveDayConfig(LocalDate date, WorkCalendarDay calendarDay, AttendanceSettings settings) {
        if (calendarDay != null) {
            return new EffectiveDayConfig(
                    date,
                    calendarDay.getDayType(),
                    calendarDay.getShiftStartLocal(),
                    calendarDay.getGracePeriodMinutes(),
                    calendarDay.getAbsenceCutoffLocal(),
                    calendarDay.getShiftEndLocal(),
                    ZoneId.of(calendarDay.getTimezoneId()),
                    calendarDay.getNote()
            );
        }
        DayType fallbackType = date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY
                ? DayType.WEEKLY_OFF
                : DayType.WORKING;
        return new EffectiveDayConfig(
                date,
                fallbackType,
                settings.getDefaultShiftStartLocal(),
                settings.getDefaultGracePeriodMinutes(),
                settings.getDefaultAbsenceCutoffLocal(),
                settings.getDefaultShiftEndLocal(),
                resolveZone(settings),
                null
        );
    }

    public AttendanceStatus evaluateCheckInStatus(Instant checkInUtc, EffectiveDayConfig dayConfig) {
        ZonedDateTime localCheckIn = checkInUtc.atZone(dayConfig.timezone());
        LocalDateTime shiftStart = LocalDateTime.of(dayConfig.date(), dayConfig.shiftStartLocal());
        LocalDateTime graceDeadline = shiftStart.plusMinutes(dayConfig.gracePeriodMinutes());
        return !localCheckIn.toLocalDateTime().isAfter(graceDeadline)
                ? AttendanceStatus.ON_TIME
                : AttendanceStatus.LATE;
    }

    public LogoutStatus evaluateCheckOutStatus(Instant checkOutUtc, EffectiveDayConfig dayConfig) {
        LocalDateTime localCheckOut = checkOutUtc.atZone(dayConfig.timezone()).toLocalDateTime();
        LocalDateTime shiftEnd = resolveShiftEndLocalDateTime(dayConfig);
        return localCheckOut.isBefore(shiftEnd)
                ? LogoutStatus.EARLY_LOGOUT
                : LogoutStatus.EXTENDED_WORK_HOURS;
    }

    public LocalDateTime resolveShiftEndLocalDateTime(EffectiveDayConfig dayConfig) {
        LocalTime shiftEnd = dayConfig.shiftEndLocal() != null
                ? dayConfig.shiftEndLocal()
                : dayConfig.absenceCutoffLocal();
        LocalDate endDate = shiftEnd.isBefore(dayConfig.shiftStartLocal())
                ? dayConfig.date().plusDays(1)
                : dayConfig.date();
        return LocalDateTime.of(endDate, shiftEnd);
    }

    public DerivedGridState deriveDayState(
            EffectiveDayConfig dayConfig,
            AttendanceRecord existingRecord,
            Instant currentUtc
    ) {
        return deriveDayState(dayConfig, existingRecord, null, currentUtc);
    }

    public DerivedGridState deriveDayState(
            EffectiveDayConfig dayConfig,
            AttendanceRecord existingRecord,
            LeaveRecord leaveRecord,
            Instant currentUtc
    ) {
        if (dayConfig.dayType() == DayType.HOLIDAY) {
            return DerivedGridState.HOLIDAY;
        }
        if (dayConfig.dayType() == DayType.WEEKLY_OFF) {
            return DerivedGridState.WEEKLY_OFF;
        }
        if (existingRecord != null) {
            return existingRecord.getAttendanceStatus() == AttendanceStatus.LATE
                    ? DerivedGridState.LATE
                    : DerivedGridState.ON_TIME;
        }
        if (leaveRecord != null) {
            return DerivedGridState.LEAVE;
        }
        LocalDateTime cutoffDateTime = resolveCutoffLocalDateTime(dayConfig);
        LocalDateTime nowLocal = currentUtc.atZone(dayConfig.timezone()).toLocalDateTime();
        return nowLocal.isAfter(cutoffDateTime) ? DerivedGridState.ABSENT : DerivedGridState.NOT_DUE;
    }

    public LocalDateTime resolveCutoffLocalDateTime(EffectiveDayConfig dayConfig) {
        LocalDate cutoffDate = dayConfig.absenceCutoffLocal().isBefore(dayConfig.shiftStartLocal())
                ? dayConfig.date().plusDays(1)
                : dayConfig.date();
        return LocalDateTime.of(cutoffDate, dayConfig.absenceCutoffLocal());
    }

    public String formatLocalTime(Instant utcInstant, ZoneId zone) {
        return utcInstant.atZone(zone).toLocalTime().format(LOCAL_TIME_DISPLAY);
    }

    /**
     * Computes great-circle distance in meters between two WGS84 coordinates using Haversine.
     */
    public double haversineDistanceMeters(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double dLat = Math.toRadians(lat2Deg - lat1Deg);
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double a = Math.pow(Math.sin(dLat / 2.0), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2.0), 2);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_METERS * c;
    }

    public record EffectiveDayConfig(
            LocalDate date,
            DayType dayType,
            LocalTime shiftStartLocal,
            int gracePeriodMinutes,
            LocalTime absenceCutoffLocal,
            LocalTime shiftEndLocal,
            ZoneId timezone,
            String note
    ) {
        public EffectiveDayConfig(
                LocalDate date,
                DayType dayType,
                LocalTime shiftStartLocal,
                int gracePeriodMinutes,
                LocalTime absenceCutoffLocal,
                ZoneId timezone,
                String note
        ) {
            this(date, dayType, shiftStartLocal, gracePeriodMinutes, absenceCutoffLocal, absenceCutoffLocal, timezone, note);
        }
    }

    public enum DerivedGridState {
        ON_TIME,
        LATE,
        ABSENT,
        LEAVE,
        HOLIDAY,
        WEEKLY_OFF,
        NOT_DUE
    }
}
