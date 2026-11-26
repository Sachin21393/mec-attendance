package com.example.attendance.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "attendance_settings")
public class AttendanceSettings {

    @Id
    private UUID id;

    @Column(name = "workplace_name", nullable = false, length = 160)
    private String workplaceName;

    @Column(name = "timezone_id", nullable = false, length = 64)
    private String timezoneId;

    @Column(name = "default_shift_start_local", nullable = false)
    private LocalTime defaultShiftStartLocal;

    @Column(name = "default_grace_period_minutes", nullable = false)
    private int defaultGracePeriodMinutes;

    @Column(name = "default_absence_cutoff_local", nullable = false)
    private LocalTime defaultAbsenceCutoffLocal;

    @Column(name = "default_shift_end_local", nullable = false)
    private LocalTime defaultShiftEndLocal = LocalTime.of(18, 0);

    @Column(name = "proxy_enabled", nullable = false)
    private boolean proxyEnabled;

    @Column(name = "max_proxy_per_actor_per_day", nullable = false)
    private int maxProxyPerActorPerDay;

    @Column(name = "proxy_cap_counts_successful_only", nullable = false)
    private boolean proxyCapCountsSuccessfulOnly;

    @Column(name = "face_match_threshold", nullable = false)
    private double faceMatchThreshold;

    @Column(name = "face_model_version", nullable = false, length = 80)
    private String faceModelVersion;

    @Column(name = "geofence_enabled", nullable = false)
    private boolean geofenceEnabled = false;

    @Column(name = "geofence_latitude")
    private Double geofenceLatitude;

    @Column(name = "geofence_longitude")
    private Double geofenceLongitude;

    @Column(name = "geofence_radius_meters", nullable = false)
    private int geofenceRadiusMeters = 50;

    @Column(name = "effective_from_utc", nullable = false)
    private Instant effectiveFromUtc;

    @Column(name = "updated_by_admin_id")
    private UUID updatedByAdminId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AttendanceSettings() {
    }

    public AttendanceSettings(
            UUID id,
            String workplaceName,
            String timezoneId,
            LocalTime defaultShiftStartLocal,
            int defaultGracePeriodMinutes,
            LocalTime defaultAbsenceCutoffLocal,
            boolean proxyEnabled,
            int maxProxyPerActorPerDay,
            boolean proxyCapCountsSuccessfulOnly,
            double faceMatchThreshold,
            String faceModelVersion,
            Instant effectiveFromUtc,
            UUID updatedByAdminId,
            Instant now
    ) {
        this.id = id;
        this.workplaceName = workplaceName;
        this.timezoneId = timezoneId;
        this.defaultShiftStartLocal = defaultShiftStartLocal;
        this.defaultGracePeriodMinutes = defaultGracePeriodMinutes;
        this.defaultAbsenceCutoffLocal = defaultAbsenceCutoffLocal;
        this.defaultShiftEndLocal = LocalTime.of(18, 0);
        this.proxyEnabled = proxyEnabled;
        this.maxProxyPerActorPerDay = maxProxyPerActorPerDay;
        this.proxyCapCountsSuccessfulOnly = proxyCapCountsSuccessfulOnly;
        this.faceMatchThreshold = faceMatchThreshold;
        this.faceModelVersion = faceModelVersion;
        this.effectiveFromUtc = effectiveFromUtc;
        this.updatedByAdminId = updatedByAdminId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getWorkplaceName() {
        return workplaceName;
    }

    public void setWorkplaceName(String workplaceName) {
        this.workplaceName = workplaceName;
    }

    public String getTimezoneId() {
        return timezoneId;
    }

    public void setTimezoneId(String timezoneId) {
        this.timezoneId = timezoneId;
    }

    public LocalTime getDefaultShiftStartLocal() {
        return defaultShiftStartLocal;
    }

    public void setDefaultShiftStartLocal(LocalTime defaultShiftStartLocal) {
        this.defaultShiftStartLocal = defaultShiftStartLocal;
    }

    public int getDefaultGracePeriodMinutes() {
        return defaultGracePeriodMinutes;
    }

    public void setDefaultGracePeriodMinutes(int defaultGracePeriodMinutes) {
        this.defaultGracePeriodMinutes = defaultGracePeriodMinutes;
    }

    public LocalTime getDefaultAbsenceCutoffLocal() {
        return defaultAbsenceCutoffLocal;
    }

    public void setDefaultAbsenceCutoffLocal(LocalTime defaultAbsenceCutoffLocal) {
        this.defaultAbsenceCutoffLocal = defaultAbsenceCutoffLocal;
    }

    public LocalTime getDefaultShiftEndLocal() {
        return defaultShiftEndLocal;
    }

    public void setDefaultShiftEndLocal(LocalTime defaultShiftEndLocal) {
        this.defaultShiftEndLocal = defaultShiftEndLocal;
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    public void setProxyEnabled(boolean proxyEnabled) {
        this.proxyEnabled = proxyEnabled;
    }

    public int getMaxProxyPerActorPerDay() {
        return maxProxyPerActorPerDay;
    }

    public void setMaxProxyPerActorPerDay(int maxProxyPerActorPerDay) {
        this.maxProxyPerActorPerDay = maxProxyPerActorPerDay;
    }

    public boolean isProxyCapCountsSuccessfulOnly() {
        return proxyCapCountsSuccessfulOnly;
    }

    public void setProxyCapCountsSuccessfulOnly(boolean proxyCapCountsSuccessfulOnly) {
        this.proxyCapCountsSuccessfulOnly = proxyCapCountsSuccessfulOnly;
    }

    public double getFaceMatchThreshold() {
        return faceMatchThreshold;
    }

    public void setFaceMatchThreshold(double faceMatchThreshold) {
        this.faceMatchThreshold = faceMatchThreshold;
    }

    public String getFaceModelVersion() {
        return faceModelVersion;
    }

    public void setFaceModelVersion(String faceModelVersion) {
        this.faceModelVersion = faceModelVersion;
    }

    public boolean isGeofenceEnabled() {
        return geofenceEnabled;
    }

    public void setGeofenceEnabled(boolean geofenceEnabled) {
        this.geofenceEnabled = geofenceEnabled;
    }

    public Double getGeofenceLatitude() {
        return geofenceLatitude;
    }

    public void setGeofenceLatitude(Double geofenceLatitude) {
        this.geofenceLatitude = geofenceLatitude;
    }

    public Double getGeofenceLongitude() {
        return geofenceLongitude;
    }

    public void setGeofenceLongitude(Double geofenceLongitude) {
        this.geofenceLongitude = geofenceLongitude;
    }

    public int getGeofenceRadiusMeters() {
        return geofenceRadiusMeters;
    }

    public void setGeofenceRadiusMeters(int geofenceRadiusMeters) {
        this.geofenceRadiusMeters = geofenceRadiusMeters;
    }

    public Instant getEffectiveFromUtc() {
        return effectiveFromUtc;
    }

    public void setEffectiveFromUtc(Instant effectiveFromUtc) {
        this.effectiveFromUtc = effectiveFromUtc;
    }

    public UUID getUpdatedByAdminId() {
        return updatedByAdminId;
    }

    public void setUpdatedByAdminId(UUID updatedByAdminId) {
        this.updatedByAdminId = updatedByAdminId;
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
