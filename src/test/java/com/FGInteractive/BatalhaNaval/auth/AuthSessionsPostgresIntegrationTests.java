package com.FGInteractive.BatalhaNaval.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
@Transactional
class AuthSessionsPostgresIntegrationTests {
    @Autowired MockMvc mvc;

    private String account(String suffix) throws Exception {
        String email = "captain-" + suffix + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Captain%s","email":"%s","password":"safe-password-123"}
                """.formatted(suffix,email)))
            .andExpect(status().isCreated());
        return email;
    }

    private String login(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"safe-password-123"}
                """.formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andExpect(jsonPath("$.user.email").value(email))
            .andExpect(jsonPath("$.user.avatarId").value("captain"))
            .andReturn().getResponse().getContentAsString();
    }

    private static String claim(String json, String path) {
        return JsonPath.read(json, path);
    }

    @Test
    void anonymousAccessIsDeniedForPrivateProfile() throws Exception {
        String email = account(UUID.randomUUID().toString().substring(0, 8));
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"Hijack\"}")).andExpect(status().isUnauthorized());
        String access = claim(login(email), "$.accessToken");
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));

        // Tampering with the JWT must not produce a valid authenticated request.
        String[] parts = access.split("\\.");
        String corrupted = parts[0] + "." + parts[1] + "." + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + corrupted))
            .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"not-the-password"}
                """.formatted(email)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refreshRotatesCredentialAndLogoutInvalidatesAccessImmediately() throws Exception {
        String email = account(UUID.randomUUID().toString().substring(0, 8));
        String first = login(email);
        String oldAccess = claim(first, "$.accessToken");
        String oldRefresh = claim(first, "$.refreshToken");
        String second = mvc.perform(post("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String nextAccess = claim(second, "$.accessToken");
        String nextRefresh = claim(second, "$.refreshToken");
        assertNotEquals(oldRefresh, nextRefresh);
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
            .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + nextAccess))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + nextAccess))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + oldAccess))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + nextRefresh + "\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void secondLoginInvalidatesPreviousSessionButNotAnotherPlayer() throws Exception {
        String emailA = account(UUID.randomUUID().toString().substring(0, 8));
        String emailB = account(UUID.randomUUID().toString().substring(0, 8));
        String firstA = login(emailA);
        String jwtA = claim(firstA, "$.accessToken");
        String refreshA = claim(firstA, "$.refreshToken");
        String jwtB = claim(login(emailB), "$.accessToken");

        String secondA = login(emailA);
        String jwtNew = claim(secondA, "$.accessToken");

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + jwtA))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("{\"refreshToken\":\"" + refreshA + "\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + jwtNew))
            .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + jwtB))
            .andExpect(status().isOk());

        // Redundant endpoints must not remain part of the public API.
        mvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + jwtNew))
            .andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer " + jwtNew))
            .andExpect(status().isNotFound());
    }
}
