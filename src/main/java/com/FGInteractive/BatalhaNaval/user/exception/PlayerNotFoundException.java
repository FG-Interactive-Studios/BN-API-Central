package com.FGInteractive.BatalhaNaval.user.exception;

import com.FGInteractive.BatalhaNaval.shared.exception.AppException;
public class PlayerNotFoundException extends AppException {
    public PlayerNotFoundException() { super("Player not found"); }
}
