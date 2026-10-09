package com.FGInteractive.BatalhaNaval.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.FGInteractive.BatalhaNaval.auth.repository.AuthRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class AccountSecurityPostgresIntegrationTests {
    private static final String OLD_PASSWORD = "old-password-123";
    private static final String NEW_PASSWORD = "new-password-456";

    @Autowired MockMvc mvc;
    @Autowired AuthRepository credentials;
    @Autowired PasswordEncoder encoder;

    private String register() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "security-" + marker + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Security%s","email":"%s","password":"%s"}
                """.formatted(marker, email, OLD_PASSWORD)))
            .andExpect(status().isCreated());
        return email;
    }

    private String login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, password)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private static String value(String json, String path) {
        return JsonPath.read(json, path);
    }

    @Test
    void passwordChangeRequiresAuthenticationAndCorrectCurrentPassword() throws Exception {
        String email = register();
        String auth = login(email, OLD_PASSWORD);
        String token = value(auth, "$.accessToken");

        mvc.perform(post("/api/auth/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(OLD_PASSWORD, NEW_PASSWORD)))
            .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/change-password")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"incorrect-current","newPassword":"%s"}
                """.formatted(NEW_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"))
            .andExpect(jsonPath("$.fieldErrors.currentPassword").exists())
            .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // Failed changes must leave the session and password untouched.
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        assertTrue(encoder.matches(OLD_PASSWORD,
            credentials.findByEmail(email).orElseThrow().getPasswordHash()));
    }

    @Test
    void passwordChangeKeepsSessionAndTokensValidWithNormalRefreshRotation() throws Exception {
        String email = register();
        String otherEmail = register();
        String otherAccess = value(login(otherEmail, OLD_PASSWORD), "$.accessToken");
        String first = login(email, OLD_PASSWORD);
        String access = value(first, "$.accessToken");
        String refresh = value(first, "$.refreshToken");

        mvc.perform(post("/api/auth/change-password")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(OLD_PASSWORD, NEW_PASSWORD)))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        String storedHash = credentials.findByEmail(email).orElseThrow().getPasswordHash();
        assertTrue(storedHash.startsWith("$2"));
        assertNotEquals(NEW_PASSWORD, storedHash);
        assertFalse(encoder.matches(OLD_PASSWORD, storedHash));
        assertTrue(encoder.matches(NEW_PASSWORD, storedHash));

        // A successful password change does NOT log the player out.
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email));

        // The existing refresh token is still usable and rotates as usual.
        String renewed = mvc.perform(post("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refresh + "\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String renewedAccess = value(renewed, "$.accessToken");
        String renewedRefresh = value(renewed, "$.refreshToken");
        assertNotEquals(refresh, renewedRefresh);

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + renewedAccess))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refresh + "\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + renewedRefresh + "\"}"))
            .andExpect(status().isOk());

        // Old credentials are rejected for new logins; the current session survives.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, OLD_PASSWORD)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + renewedAccess))
            .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + otherAccess))
            .andExpect(status().isOk());

        // A deliberate new login with the new password still replaces the old
        // session, as required by the single-session-per-account policy.
        String freshAccess = value(login(email, NEW_PASSWORD), "$.accessToken");
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + renewedAccess))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + freshAccess))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void rejectsReusedWeakBlankAndOversizeUtf8PasswordsWithoutLoggingOut() throws Exception {
        String email = register();
        String token = value(login(email, OLD_PASSWORD), "$.accessToken");

        mvc.perform(post("/api/auth/change-password")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(OLD_PASSWORD, OLD_PASSWORD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PASSWORD_REUSE"));

        mvc.perform(post("/api/auth/change-password")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + OLD_PASSWORD + "\",\"newPassword\":\"short\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.newPassword").exists());

        mvc.perform(post("/api/auth/change-password")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + OLD_PASSWORD + "\",\"newPassword\":\"   \"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/api/auth/change-password")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.currentPassword").exists());

        mvc.perform(post("/api/auth/change-password")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"currentPassword\":\"" + OLD_PASSWORD
                + "\",\"newPassword\":\"" + "á".repeat(40) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.newPassword").exists());

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
        assertTrue(encoder.matches(OLD_PASSWORD,
            credentials.findByEmail(email).orElseThrow().getPasswordHash()));
    }

    @Test
    void ignoresAnyClaimedUserIdAndChangesOnlyTheAuthenticatedAccount() throws Exception {
        String accountA = register(), accountB = register();
        String first = login(accountA, OLD_PASSWORD);
        String token = value(first, "$.accessToken");

        mvc.perform(post("/api/auth/change-password")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"userId":999999999,"currentPassword":"%s","newPassword":"%s"}
                """.formatted(OLD_PASSWORD, NEW_PASSWORD)))
            .andExpect(status().isNoContent());

        assertTrue(encoder.matches(NEW_PASSWORD,
            credentials.findByEmail(accountA).orElseThrow().getPasswordHash()));
        assertTrue(encoder.matches(OLD_PASSWORD,
            credentials.findByEmail(accountB).orElseThrow().getPasswordHash()));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"%s"}
                """.formatted(accountB, NEW_PASSWORD)))
            .andExpect(status().isUnauthorized());
    }
}
