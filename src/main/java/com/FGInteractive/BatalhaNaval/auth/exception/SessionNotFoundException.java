package com.FGInteractive.BatalhaNaval.auth.exception;

import com.FGInteractive.BatalhaNaval.shared.exception.AppException;

public class SessionNotFoundException extends AppException {
    public SessionNotFoundException() {
        super("Session not found");
    }
}
