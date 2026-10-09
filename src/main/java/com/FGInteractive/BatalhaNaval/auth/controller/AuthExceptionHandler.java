package com.FGInteractive.BatalhaNaval.auth.controller;


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import com.FGInteractive.BatalhaNaval.auth.dto.AuthErrorResponse;
import com.FGInteractive.BatalhaNaval.auth.exception.*;

@RestControllerAdvice(basePackages = "com.FGInteractive.BatalhaNaval.auth.controller")
public class AuthExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<AuthErrorResponse> invalid(MethodArgumentNotValidException ex) {
        Map<String,String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Registration data is invalid", fields);
    }
    @ExceptionHandler(RegistrationValidationException.class)
    public ResponseEntity<AuthErrorResponse> invalidRegistration(RegistrationValidationException ex) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), Map.of(ex.getField(), ex.getMessage()));
    }
    @ExceptionHandler(RegistrationConflictException.class)
    public ResponseEntity<AuthErrorResponse> conflict(RegistrationConflictException ex) {
        return response(HttpStatus.CONFLICT, "REGISTRATION_CONFLICT", ex.getMessage(),
            ex.getField() == null ? Map.of() : Map.of(ex.getField(), ex.getMessage()));
    }
    private ResponseEntity<AuthErrorResponse> response(HttpStatus status, String code, String message, Map<String,String> fields) {
        return ResponseEntity.status(status).body(new AuthErrorResponse(Instant.now(), status.value(), code, message, fields));
    }
}
