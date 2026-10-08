package com.FGInteractive.BatalhaNaval.auth.exception;


import com.FGInteractive.BatalhaNaval.shared.exception.AppException;
public class RegistrationConflictException extends AppException {
    private final String field;
    public RegistrationConflictException(String field,String message) { super(message); this.field=field; }
    public RegistrationConflictException(String field,String message,Throwable cause) { super(message,cause); this.field=field; }
    public String getField() { return field; }
}
