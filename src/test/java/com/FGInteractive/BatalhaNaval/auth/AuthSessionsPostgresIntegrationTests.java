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
        String nickname = "Captain" + suffix;
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"%s","email":"%s","password":"safe-password-123"}
                """.formatted(nickname, email)))
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
            .andReturn().getResponse().getContentAsString();
    }

    private static String claim(String json, String path) {
        return JsonPath.read(json, path);
    }

    @Test
    void anonymousEndpointsAndTokenControlledProfile() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = account(suffix);
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/sessions")).andExpect(status().isUnauthorized());

        String access = claim(login(email), "$.accessToken");
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nickname").value("Captain" + suffix))
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String corrupted = access.substring(0, access.length() - 1)
            + (access.endsWith("A") ? "B" : "A");
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
    void sessionManagementIsScopedToAuthenticatedPlayer() throws Exception {
        String emailA = account(UUID.randomUUID().toString().substring(0, 8));
        String emailB = account(UUID.randomUUID().toString().substring(0, 8));
        String accessA = claim(login(emailA), "$.accessToken");
        String accessB = claim(login(emailB), "$.accessToken");
        String ownSessions = mvc.perform(get("/api/auth/sessions")
            .header("Authorization", "Bearer " + accessA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].current").value(true))
            .andExpect(jsonPath("$.length()").value(1))
            .andReturn().getResponse().getContentAsString();

        String sessionId = claim(ownSessions, "$[0].id");
        mvc.perform(delete("/api/auth/sessions/" + sessionId)
            .header("Authorization", "Bearer " + accessB))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessA))
            .andExpect(status().isOk());

        mvc.perform(delete("/api/auth/sessions/" + sessionId)
            .header("Authorization", "Bearer " + accessA))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessA))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessB))
            .andExpect(status().isOk());
    }

    @Test
    void logoutAllRevokesOnlyOwnSessions() throws Exception {
        String emailA = account(UUID.randomUUID().toString().substring(0, 8));
        String emailB = account(UUID.randomUUID().toString().substring(0, 8));
        String a1 = claim(login(emailA), "$.accessToken");
        String a2 = claim(login(emailA), "$.accessToken");
        String b = claim(login(emailB), "$.accessToken");

        mvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer " + a1))
            .andExpect(status().isNoContent());
        for (String token : new String[] {a1, a2}) {
            mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + b))
            .andExpect(status().isOk());
    }
}
