package com.FGInteractive.BatalhaNaval.auth.exception;

import com.FGInteractive.BatalhaNaval.shared.exception.AppException;

public class PasswordChangeException extends AppException {
    private final String code;
    private final String field;

    public PasswordChangeException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String getCode() { return code; }
    public String getField() { return field; }
}
