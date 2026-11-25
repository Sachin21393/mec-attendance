package com.example.attendance.reports;

import java.util.List;

public final class CsvExportUtil {

    private CsvExportUtil() {
    }

    public static String toCsvRow(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCell(cells.get(i)));
        }
        sb.append("\r\n");
        return sb.toString();
    }

    public static String escapeCell(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = neutralizeFormulaInjection(raw);
        boolean needsQuotes = sanitized.indexOf(',') >= 0
                || sanitized.indexOf('"') >= 0
                || sanitized.indexOf('\n') >= 0
                || sanitized.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return sanitized;
        }
        return "\"" + sanitized.replace("\"", "\"\"") + "\"";
    }

    public static String neutralizeFormulaInjection(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }
}
