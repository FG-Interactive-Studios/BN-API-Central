package com.FGInteractive.BatalhaNaval.user.exception;

import com.FGInteractive.BatalhaNaval.shared.exception.AppException;
public class NicknameTakenException extends AppException {
    public NicknameTakenException() { super("nickname is already in use"); }
    public NicknameTakenException(Throwable cause) { super("nickname is already in use", cause); }
}
