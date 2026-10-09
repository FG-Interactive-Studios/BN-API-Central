package com.FGInteractive.BatalhaNaval.auth.exception;


import com.FGInteractive.BatalhaNaval.shared.exception.AppException;
public class RegistrationValidationException extends AppException {
    private final String field;
    public RegistrationValidationException(String field,String message) { super(message); this.field=field; }
    public String getField() { return field; }
}
