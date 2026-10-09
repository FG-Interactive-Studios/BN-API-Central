package com.FGInteractive.BatalhaNaval.auth.service;


import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import com.FGInteractive.BatalhaNaval.auth.dto.*;
import com.FGInteractive.BatalhaNaval.auth.exception.*;
import com.FGInteractive.BatalhaNaval.auth.model.Auth;
import com.FGInteractive.BatalhaNaval.auth.repository.AuthRepository;
import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import jakarta.validation.Validator;

@SpringBootTest @ActiveProfiles("test") @Transactional
class AuthServiceRegistrationTests {
    @Autowired AuthService service;
    @Autowired AuthRepository authRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder encoder;
    @Autowired Validator validator;

    @Test void createsAccountWithHashedPassword() {
        RegisterResponse created=service.register(new RegisterRequest("Captain","CAPTAIN@Example.com","safe-password-123"));
        assertNotNull(created.id());
        assertEquals("captain@example.com",created.email());
        assertTrue(userRepository.existsByNicknameIgnoreCase("captain"));
        Auth credentials=authRepository.findByEmail(created.email()).orElseThrow();
        assertNotEquals("safe-password-123",credentials.getPasswordHash());
        assertTrue(encoder.matches("safe-password-123",credentials.getPasswordHash()));
    }
    @Test void duplicateEmailIsRejected() {
        service.register(new RegisterRequest("CaptainOne","PLAYER@example.com","safe-password-123"));
        var ex=assertThrows(RegistrationConflictException.class,()->service.register(
            new RegisterRequest("CaptainTwo","player@example.com","safe-password-123")));
        assertEquals("email",ex.getField());
    }
    @Test void duplicateNicknameIsRejected() {
        service.register(new RegisterRequest("Captain","one@example.com","safe-password-123"));
        var ex=assertThrows(RegistrationConflictException.class,()->service.register(
            new RegisterRequest("captain","two@example.com","safe-password-123")));
        assertEquals("nickname",ex.getField());
    }
    @Test void validatesFields() {
        var fields=validator.validate(new RegisterRequest("x","not-an-email","short")).stream()
            .map(v->v.getPropertyPath().toString()).collect(Collectors.toSet());
        assertTrue(fields.contains("nickname"));
        assertTrue(fields.contains("email"));
        assertTrue(fields.contains("password"));
    }
    @Test void rejectsOversizedUtf8Password() {
        assertThrows(RegistrationValidationException.class,()->service.register(
            new RegisterRequest("Captain","captain@example.com","á".repeat(40))));
    }
}
