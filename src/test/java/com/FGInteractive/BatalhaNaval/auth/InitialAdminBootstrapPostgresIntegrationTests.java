package com.FGInteractive.BatalhaNaval.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.FGInteractive.BatalhaNaval.auth.repository.AuthRepository;
import com.FGInteractive.BatalhaNaval.auth.service.InitialAdminBootstrapService;
import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("ci")
@Transactional
class InitialAdminBootstrapPostgresIntegrationTests {
    @Autowired InitialAdminBootstrapService bootstrap;
    @Autowired UserRepository users;
    @Autowired AuthRepository auth;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path temp;

    @Test void startsOnlyWhenNoAdminAndWritesPrivateStrongCredentials() throws Exception {
        // Changes are rolled back after test, preserving existing CI fixtures.
        jdbc.update("UPDATE users SET is_admin = FALSE");
        Path output = temp.resolve("secrets").resolve("admin.txt");
        assertFalse(Files.exists(output));
        assertTrue(bootstrap.createIfMissing(output));
        assertTrue(users.existsByAdminTrue());

        var properties = Files.readAllLines(output,StandardCharsets.UTF_8).stream()
            .map(line -> line.split("=",2))
            .collect(Collectors.toMap(parts->parts[0],parts->parts[1]));
        String email=properties.get("email"), password=properties.get("password");
        assertNotNull(email);
        assertEquals(43,password.length()); // 32 cryptographically random bytes, Base64URL
        assertTrue(password.matches("[A-Za-z0-9_-]{43}"));
        var saved=auth.findByEmail(email).orElseThrow();
        assertTrue(saved.getUser().isAdmin());
        assertTrue(saved.getUser().getNickname().startsWith("bn-admin-"));
        assertNotEquals(password,saved.getPasswordHash());
        assertTrue(encoder.matches(password,saved.getPasswordHash()));

        if(Files.getFileStore(output).supportsFileAttributeView("posix")) {
            assertEquals("rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(output)));
        }
        String before=Files.readString(output);
        assertFalse(bootstrap.createIfMissing(output));
        assertEquals(before,Files.readString(output));
    }

    @Test void refusesToOverwriteOldSecretWhenDatabaseHasNoAdmin() throws Exception {
        jdbc.update("UPDATE users SET is_admin = FALSE");
        Path output=temp.resolve("existing-admin.txt");
        Files.writeString(output,"old-credentials-should-not-be-overwritten");
        assertThrows(IllegalStateException.class,()->bootstrap.createIfMissing(output));
        assertEquals("old-credentials-should-not-be-overwritten",
            Files.readString(output));
        assertFalse(users.existsByAdminTrue());
    }
}
