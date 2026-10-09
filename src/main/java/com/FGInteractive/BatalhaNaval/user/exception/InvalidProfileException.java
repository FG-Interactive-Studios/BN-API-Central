package com.FGInteractive.BatalhaNaval.user.exception;

import com.FGInteractive.BatalhaNaval.shared.exception.AppException;
public class InvalidProfileException extends AppException {
    private final String field;
    public InvalidProfileException(String field, String message) {
        super(message);
        this.field=field;
    }
    public String getField() { return field; }
}
