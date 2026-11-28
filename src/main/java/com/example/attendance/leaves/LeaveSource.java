package com.example.attendance.leaves;

public enum LeaveSource {
    EMPLOYEE_SELF("Employee Marked"),
    AUTO_CUTOFF("Auto Past Cutoff"),
    ADMIN("Administrator");

    private final String displayLabel;

    LeaveSource(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }
}
