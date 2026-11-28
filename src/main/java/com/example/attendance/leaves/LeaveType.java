package com.example.attendance.leaves;

public enum LeaveType {
    SICK_LEAVE("Sick / Paid Leave"),
    ANNUAL_LEAVE("Annual Leave");

    private final String displayLabel;

    LeaveType(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }
}
