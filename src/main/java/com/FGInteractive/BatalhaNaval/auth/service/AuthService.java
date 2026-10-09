package com.FGInteractive.BatalhaNaval.auth.service;


import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.FGInteractive.BatalhaNaval.auth.dto.*;
import com.FGInteractive.BatalhaNaval.auth.exception.*;
import com.FGInteractive.BatalhaNaval.auth.model.Auth;
import com.FGInteractive.BatalhaNaval.auth.repository.AuthRepository;
import com.FGInteractive.BatalhaNaval.user.model.User;
import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;

@Service
public class AuthService {
    private final AuthRepository authRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder encoder;
    public AuthService(AuthRepository authRepository,UserRepository userRepository,PasswordEncoder encoder) {
        this.authRepository=authRepository; this.userRepository=userRepository; this.encoder=encoder;
    }
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String nickname=nickname(request.nickname());
        String email=email(request.email());
        String password=request.password();
        if(password==null || password.isBlank() || password.length()<8 || password.length()>72) {
            throw new RegistrationValidationException("password","password must contain between 8 and 72 characters");
        }
        if(password.getBytes(StandardCharsets.UTF_8).length>72) {
            throw new RegistrationValidationException("password","password must contain at most 72 UTF-8 bytes");
        }
        if(userRepository.existsByNicknameIgnoreCase(nickname)) {
            throw new RegistrationConflictException("nickname","nickname is already in use");
        }
        if(authRepository.existsByEmail(email)) {
            throw new RegistrationConflictException("email","email is already in use");
        }
        Instant now=Instant.now();
        try {
            User user=userRepository.saveAndFlush(new User(nickname,now));
            Auth auth=authRepository.saveAndFlush(new Auth(user,email,encoder.encode(password),now));
            return new RegisterResponse(user.getId(),user.getNickname(),auth.getEmail(),user.getCreatedAt());
        } catch(DataIntegrityViolationException ex) {
            throw new RegistrationConflictException(null,"an account with the supplied registration data already exists",ex);
        }
    }
    private String nickname(String value) {
        if(value==null) throw new RegistrationValidationException("nickname","nickname is required");
        String trimmed=value.trim();
        if(trimmed.length()<3||trimmed.length()>30||trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new RegistrationValidationException("nickname","nickname must contain 3 to 30 printable characters after trimming");
        }
        return trimmed;
    }
    private String email(String value) {
        if(value==null||value.isBlank()) throw new RegistrationValidationException("email","email is required");
        String normalized=value.trim().toLowerCase(Locale.ROOT);
        if(normalized.length()>254||!normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new RegistrationValidationException("email","email must be valid and at most 254 characters");
        }
        return normalized;
    }
}
