package com.example.attendance.attendance;

public enum LogoutStatus {
    EARLY_LOGOUT("Early Logout"),
    EXTENDED_WORK_HOURS("Extended Work Hours");

    private final String displayLabel;

    LogoutStatus(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }
}
