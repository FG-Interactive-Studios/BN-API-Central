package com.FGInteractive.BatalhaNaval.user;

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
class PlayerProfilesPostgresIntegrationTests {
    @Autowired MockMvc mvc;

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String register(String suffix) throws Exception {
        String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Player%s","email":"player-%s@example.com","password":"safe-password-123"}
                """.formatted(suffix, suffix)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json;
    }

    private String token(String suffix) throws Exception {
        String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"player-%s@example.com","password":"safe-password-123"}
                """.formatted(suffix)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.accessToken");
    }

    @Test
    void publicProfileIsAnonymousAndNeverContainsPrivateAccountData() throws Exception {
        String suffix = unique();
        String user = register(suffix);
        int id = JsonPath.read(user, "$.id");
        mvc.perform(get("/api/users/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.nickname").value("Player" + suffix))
            .andExpect(jsonPath("$.avatarId").value("captain"))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.email").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.sessions").doesNotExist());

        mvc.perform(get("/api/users/999999999")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PLAYER_NOT_FOUND"));
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void changingNicknameAndAvatarUpdatesPublicAndPrivateProfileWithoutChangingJwt() throws Exception {
        String suffix = unique();
        String user = register(suffix);
        int id = JsonPath.read(user, "$.id");
        String access = token(suffix);
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"  SeaCaptain%s  ","avatarId":"submarine"}
                """.formatted(suffix)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nickname").value("SeaCaptain" + suffix))
            .andExpect(jsonPath("$.avatarId").value("submarine"))
            .andExpect(jsonPath("$.email").value("player-" + suffix + "@example.com"));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nickname").value("SeaCaptain" + suffix));
        mvc.perform(get("/api/users/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nickname").value("SeaCaptain" + suffix))
            .andExpect(jsonPath("$.avatarId").value("submarine"))
            .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void validationNicknameUniquenessAndCrossAccountModificationProtection() throws Exception {
        String a = unique(), b = unique();
        String accountA = register(a);
        String accountB = register(b);
        int userBId = JsonPath.read(accountB, "$.id");
        String accessA = token(a);

        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + accessA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.nickname").exists());
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + accessA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"avatarId\":\"https://example.com/tracker.png\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + accessA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + accessA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"player%s"}
                """.formatted(b)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("NICKNAME_TAKEN"));

        // A payload does not accept an identity override or change another user's email.
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + accessA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"avatarId":"carrier","id":%d,"email":"evil@example.com"}
                """.formatted(userBId)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/users/" + userBId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nickname").value("Player" + b))
            .andExpect(jsonPath("$.avatarId").value("captain"));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.avatarId").value("carrier"))
            .andExpect(jsonPath("$.email").value("player-" + a + "@example.com"));
        mvc.perform(patch("/api/users/" + userBId)
            .header("Authorization", "Bearer " + accessA)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"Hijacked\"}")).andExpect(status().isNotFound());
    }

    @Test
    void nicknameOnlyCaseChangeAndAvatarOnlyUpdateAreAllowed() throws Exception {
        String suffix = unique();
        register(suffix);
        String access = token(suffix);
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"nickname\":\"player" + suffix + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nickname").value("player" + suffix));
        mvc.perform(patch("/api/users/me").header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"avatarId\":\"destroyer\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.avatarId").value("destroyer"));
    }
}
