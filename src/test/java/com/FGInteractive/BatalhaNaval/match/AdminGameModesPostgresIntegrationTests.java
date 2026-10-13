package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class AdminGameModesPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private record Player(long id, String jwt) {}

    private Player player(boolean triesToSelfPromote) throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String email="boardadmin-"+suffix+"@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Board%s","email":"%s",
                 "password":"safe-password-123","isAdmin":%s}
                """.formatted(suffix,email,triesToSelfPromote)))
            .andExpect(status().isCreated());
        String login=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"safe-password-123"}
                """.formatted(email)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Player(((Number)JsonPath.read(login,"$.user.id")).longValue(),
            JsonPath.read(login,"$.accessToken"));
    }

    private void appointAdmin(Player player) {
        assertEquals(1,jdbc.update("UPDATE users SET is_admin = TRUE WHERE id = ?",player.id()));
    }

    @AfterEach void restoreCatalog() {
        // Settings changes survive HTTP requests; reset shared CI database so
        // existing matchmaking integration tests always see all five modes.
        jdbc.update("""
            UPDATE game_mode_settings SET enabled = TRUE,
                updated_by = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE mode_id IN ('classic','quick','triangular','triangle-tiles','hexagons')
            """);
    }

    private String change(Player actor,String id,String payload,int expected) throws Exception {
        return mvc.perform(put("/api/admin/game-modes/{id}/availability",id)
            .header("Authorization","Bearer "+actor.jwt)
            .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().is(expected))
            .andReturn().getResponse().getContentAsString();
    }

    private String create(Player user,String modeId,int expected) throws Exception {
        var request=post("/api/matchmaking/lobbies")
            .header("Authorization","Bearer "+user.jwt);
        if(modeId!=null)request.contentType(MediaType.APPLICATION_JSON)
            .content("{\"modeId\":\""+modeId+"\"}");
        return mvc.perform(request).andExpect(status().is(expected))
            .andReturn().getResponse().getContentAsString();
    }

    private void leave(Player p) throws Exception {
        mvc.perform(delete("/api/matchmaking/lobbies/me")
            .header("Authorization","Bearer "+p.jwt)).andExpect(status().isNoContent());
    }

    @Test void accessIsRestrictedAndRegistrationCannotGrantAdmin() throws Exception {
        mvc.perform(get("/api/admin/game-modes")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/game-modes/quick/availability")
            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
            .andExpect(status().isUnauthorized());
        Player ordinary=player(true);
        assertEquals(false,jdbc.queryForObject(
            "SELECT is_admin FROM users WHERE id = ?",Boolean.class,ordinary.id()));
        mvc.perform(get("/api/admin/game-modes")
            .header("Authorization","Bearer "+ordinary.jwt)).andExpect(status().isForbidden());
        change(ordinary,"quick","{\"enabled\":false}",403);
        assertEquals(true,jdbc.queryForObject(
            "SELECT enabled FROM game_mode_settings WHERE mode_id='quick'",Boolean.class));
    }

    @Test void adminCanToggleCatalogAndSettingsSurviveHttpRequests() throws Exception {
        Player admin=player(false),host=player(false);
        appointAdmin(admin);
        mvc.perform(get("/api/admin/game-modes")
            .header("Authorization","Bearer "+admin.jwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[?(@.mode.id == 'hexagons')]").isNotEmpty());
        String disabled=change(admin,"quick","{\"enabled\":false}",200);
        assertEquals(false,JsonPath.read(disabled,"$.enabled"));
        assertEquals(admin.id(),((Number)JsonPath.read(disabled,"$.updatedBy")).longValue());
        assertNotNull(JsonPath.read(disabled,"$.updatedAt"));
        // Repeated disable must be idempotent.
        String twice=change(admin,"quick","{\"enabled\":false}",200);
        assertEquals((String)JsonPath.read(disabled,"$.updatedAt"),
            (String)JsonPath.read(twice,"$.updatedAt"));
        mvc.perform(get("/api/game-modes")
            .header("Authorization","Bearer "+host.jwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == 'quick')]").isEmpty())
            .andExpect(jsonPath("$[?(@.id == 'classic')]").isNotEmpty());
        create(host,"quick",409);
        // Default mode remains usable and the rejected request allocated no room.
        String classic=create(host,null,201);
        assertEquals("classic",JsonPath.read(classic,"$.mode.id"));
        leave(host);
        change(admin,"quick","{\"enabled\":true}",200);
        String quick=create(host,"quick",201);
        assertEquals("quick",JsonPath.read(quick,"$.mode.id"));
        leave(host);
    }

    @Test void disabledModeDoesNotAlterExistingLobbyOrItsImmutableModeDefinition() throws Exception {
        Player admin=player(false),host=player(false),guest=player(false),other=player(false);
        appointAdmin(admin);
        try {
            String existing=create(host,"hexagons",201);
            String code=JsonPath.read(existing,"$.code");
            change(admin,"hexagons","{\"enabled\":false}",200);
            create(other,"hexagons",409);
            mvc.perform(post("/api/matchmaking/lobbies/join")
                .header("Authorization","Bearer "+guest.jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\""+code+"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode.id").value("hexagons"));
            for(Player participant:new Player[]{host,guest}) {
                mvc.perform(put("/api/matchmaking/lobbies/me/ready")
                    .header("Authorization","Bearer "+participant.jwt)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"ready\":true}"))
                    .andExpect(status().isOk());
            }
            mvc.perform(post("/api/matches/me/placement/random")
                .header("Authorization","Bearer "+host.jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode.id").value("hexagons"));
        } finally {
            leave(host);
        }
    }

    @Test void lastEnabledModeCannotBeDisabledAndBadInputsNeverMutateState() throws Exception {
        Player admin=player(false);appointAdmin(admin);
        change(admin,"bad-mode","{\"enabled\":false}",404);
        change(admin,"classic","{}",400);
        change(admin,"classic","{\"enabled\":null}",400);
        for(String id:new String[]{"quick","triangular","triangle-tiles","hexagons"}) {
            change(admin,id,"{\"enabled\":false}",200);
        }
        change(admin,"classic","{\"enabled\":false}",409);
        assertEquals(true,jdbc.queryForObject(
            "SELECT enabled FROM game_mode_settings WHERE mode_id='classic'",Boolean.class));
        mvc.perform(get("/api/game-modes")
            .header("Authorization","Bearer "+admin.jwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test void revokingAdministratorInDbBlocksAlreadyIssuedJwt() throws Exception {
        Player actor=player(false);
        appointAdmin(actor);
        mvc.perform(get("/api/admin/game-modes")
            .header("Authorization","Bearer "+actor.jwt))
            .andExpect(status().isOk());
        jdbc.update("UPDATE users SET is_admin = FALSE WHERE id = ?",actor.id());
        mvc.perform(get("/api/admin/game-modes")
            .header("Authorization","Bearer "+actor.jwt))
            .andExpect(status().isForbidden());
        change(actor,"hexagons","{\"enabled\":false}",403);
    }
}
