package com.FGInteractive.BatalhaNaval.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.FGInteractive.BatalhaNaval.auth.dto.RegisterRequest;
import com.FGInteractive.BatalhaNaval.auth.service.AuthService;
import com.FGInteractive.BatalhaNaval.auth.repository.AuthRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * CI integration smoke test against real PostgreSQL with Flyway enabled.
 * The CI job supplies DB_URL/DB_USER/DB_PASSWORD from its PostgreSQL service.
 */
@SpringBootTest
@ActiveProfiles("ci")
@Transactional
class RegistrationPostgresIntegrationTests {
    @Autowired AuthService registration;
    @Autowired AuthRepository credentials;

    @Test
    void migratesSchemaAndPersistsRegistration() {
        var user = registration.register(
            new RegisterRequest("CaptainCI", "CaptainCI@Example.com", "safe-password-123"));
        assertNotNull(user.id());
        assertEquals("captainci@example.com", user.email());
        var saved = credentials.findByEmail(user.email()).orElseThrow();
        assertEquals(user.id(), saved.getUser().getId());
        assertNotEquals("safe-password-123", saved.getPasswordHash());
        assertTrue(saved.getPasswordHash().startsWith("$2"));
    }
}
