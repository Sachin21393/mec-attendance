package com.example.attendance.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "work_calendar")
public class WorkCalendarDay {

    @Id
    private UUID id;

    @Column(name = "calendar_date", nullable = false, unique = true)
    private LocalDate calendarDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 20)
    private DayType dayType;

    @Column(name = "shift_start_local", nullable = false)
    private LocalTime shiftStartLocal;

    @Column(name = "grace_period_minutes", nullable = false)
    private int gracePeriodMinutes;

    @Column(name = "absence_cutoff_local", nullable = false)
    private LocalTime absenceCutoffLocal;

    @Column(name = "timezone_id", nullable = false, length = 64)
    private String timezoneId;

    @Column(length = 255)
    private String note;

    @Column(name = "created_by_admin_id")
    private UUID createdByAdminId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkCalendarDay() {
    }

    public WorkCalendarDay(UUID id, LocalDate calendarDate, DayType dayType, LocalTime shiftStartLocal,
                           int gracePeriodMinutes, LocalTime absenceCutoffLocal, String timezoneId,
                           String note, UUID createdByAdminId, Instant now) {
        this.id = id;
        this.calendarDate = calendarDate;
        this.dayType = dayType;
        this.shiftStartLocal = shiftStartLocal;
        this.gracePeriodMinutes = gracePeriodMinutes;
        this.absenceCutoffLocal = absenceCutoffLocal;
        this.timezoneId = timezoneId;
        this.note = note;
        this.createdByAdminId = createdByAdminId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getCalendarDate() {
        return calendarDate;
    }

    public DayType getDayType() {
        return dayType;
    }

    public void setDayType(DayType dayType) {
        this.dayType = dayType;
    }

    public LocalTime getShiftStartLocal() {
        return shiftStartLocal;
    }

    public void setShiftStartLocal(LocalTime shiftStartLocal) {
        this.shiftStartLocal = shiftStartLocal;
    }

    public int getGracePeriodMinutes() {
        return gracePeriodMinutes;
    }

    public void setGracePeriodMinutes(int gracePeriodMinutes) {
        this.gracePeriodMinutes = gracePeriodMinutes;
    }

    public LocalTime getAbsenceCutoffLocal() {
        return absenceCutoffLocal;
    }

    public void setAbsenceCutoffLocal(LocalTime absenceCutoffLocal) {
        this.absenceCutoffLocal = absenceCutoffLocal;
    }

    public String getTimezoneId() {
        return timezoneId;
    }

    public void setTimezoneId(String timezoneId) {
        this.timezoneId = timezoneId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public UUID getCreatedByAdminId() {
        return createdByAdminId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
