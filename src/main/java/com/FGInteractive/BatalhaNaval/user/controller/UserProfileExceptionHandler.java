package com.FGInteractive.BatalhaNaval.user.controller;

import com.FGInteractive.BatalhaNaval.auth.dto.AuthErrorResponse;
import com.FGInteractive.BatalhaNaval.user.exception.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackageClasses = UserController.class)
public class UserProfileExceptionHandler {
    @ExceptionHandler(InvalidProfileException.class)
    ResponseEntity<AuthErrorResponse> invalid(InvalidProfileException ex) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(),
            Map.of(ex.getField(), ex.getMessage()));
    }

    @ExceptionHandler(NicknameTakenException.class)
    ResponseEntity<AuthErrorResponse> duplicate(NicknameTakenException ex) {
        return respond(HttpStatus.CONFLICT, "NICKNAME_TAKEN", ex.getMessage(),
            Map.of("nickname", ex.getMessage()));
    }

    @ExceptionHandler(PlayerNotFoundException.class)
    ResponseEntity<AuthErrorResponse> missing(PlayerNotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", ex.getMessage(), Map.of());
    }

    private ResponseEntity<AuthErrorResponse> respond(HttpStatus status, String code,
        String message, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new AuthErrorResponse(
            Instant.now(), status.value(), code, message, fields));
    }
}
