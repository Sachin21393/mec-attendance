package com.example.attendance.common;

import org.springframework.http.HttpStatus;

public class ValidationException extends AppException {
    public ValidationException(String code, String message) {
        super(code, message, HttpStatus.BAD_REQUEST);
    }
}
