package com.FGInteractive.BatalhaNaval.auth.exception;

import com.FGInteractive.BatalhaNaval.shared.exception.AppException;

public class InvalidSessionException extends AppException {
    public InvalidSessionException() {
        super("Invalid credentials or expired session");
    }
}
