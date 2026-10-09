package com.FGInteractive.BatalhaNaval.auth.controller;

import com.FGInteractive.BatalhaNaval.auth.dto.*;
import com.FGInteractive.BatalhaNaval.auth.service.AuthService;
import com.FGInteractive.BatalhaNaval.auth.service.AccountSecurityService;
import com.FGInteractive.BatalhaNaval.auth.service.SessionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService registration;
    private final SessionService sessions;
    private final AccountSecurityService accountSecurity;

    public AuthController(AuthService registration, SessionService sessions,
                          AccountSecurityService accountSecurity) {
        this.registration = registration;
        this.sessions = sessions;
        this.accountSecurity = accountSecurity;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registration.register(request));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return sessions.login(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return sessions.refresh(request);
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt jwt,
                               @Valid @RequestBody ChangePasswordRequest request) {
        accountSecurity.changePassword(Long.parseLong(jwt.getSubject()),
            UUID.fromString(jwt.getClaimAsString("sid")), request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        sessions.logout(Long.parseLong(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")));
    }


}
