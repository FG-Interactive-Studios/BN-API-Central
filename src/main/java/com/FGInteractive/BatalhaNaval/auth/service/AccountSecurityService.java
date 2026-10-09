package com.FGInteractive.BatalhaNaval.auth.service;

import com.FGInteractive.BatalhaNaval.auth.dto.ChangePasswordRequest;
import com.FGInteractive.BatalhaNaval.auth.exception.InvalidSessionException;
import com.FGInteractive.BatalhaNaval.auth.exception.PasswordChangeException;
import com.FGInteractive.BatalhaNaval.auth.repository.AuthRepository;
import com.FGInteractive.BatalhaNaval.auth.repository.AuthSessionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountSecurityService {
    private final AuthRepository credentials;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;

    public AccountSecurityService(AuthRepository credentials,
                                  AuthSessionRepository sessions,
                                  PasswordEncoder passwordEncoder) {
        this.credentials = credentials;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void changePassword(long userId, UUID sessionId, ChangePasswordRequest request) {
        String replacement = request.newPassword();
        if (replacement.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new PasswordChangeException("VALIDATION_ERROR", "newPassword",
                "newPassword must contain at most 72 UTF-8 bytes");
        }

        // A credential-row lock serializes changes with new logins across instances.
        var credential = credentials.lockByUserId(userId)
            .orElseThrow(InvalidSessionException::new);
        if (!sessions.existsByIdAndUser_IdAndRevokedAtIsNullAndExpiresAtAfter(
            sessionId, userId, Instant.now())) {
            throw new InvalidSessionException();
        }

        if (!passwordEncoder.matches(request.currentPassword(), credential.getPasswordHash())) {
            throw new PasswordChangeException("INVALID_CURRENT_PASSWORD", "currentPassword",
                "Current password is incorrect");
        }
        if (passwordEncoder.matches(replacement, credential.getPasswordHash())) {
            throw new PasswordChangeException("PASSWORD_REUSE", "newPassword",
                "New password must differ from the current password");
        }

        credential.changePasswordHash(passwordEncoder.encode(replacement));
        credentials.saveAndFlush(credential);
        // Keep the authenticated session, access JWT and refresh token unchanged.
        // Changing a password only updates credentials; logout is a separate action.
    }
}
