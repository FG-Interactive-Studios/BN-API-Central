package com.FGInteractive.BatalhaNaval.match;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class GameModesPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach @org.junit.jupiter.api.AfterEach
    void resetCatalog() {
        jdbc.update("""
            UPDATE game_mode_settings
               SET enabled = CASE WHEN mode_id IN ('classic','classic-powers')
                                  THEN TRUE ELSE FALSE END,
                   updated_by = NULL
             WHERE mode_id IN ('classic','classic-powers','quick','triangular','triangle-tiles','hexagons')
            """);
    }

    private record Player(String jwt) {}

    private Player player() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String email="modes-"+suffix+"@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Modes%s","email":"%s","password":"safe-password-123"}
                """.formatted(suffix,email))).andExpect(status().isCreated());
        String login=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"safe-password-123"}
                """.formatted(email))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new Player(JsonPath.read(login,"$.accessToken"));
    }
    private String create(Player player, String json) throws Exception {
        var request=post("/api/matchmaking/lobbies")
            .header("Authorization","Bearer "+player.jwt);
        if(json!=null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
    }
    private String prepare(Player host, Player guest, String modeId) throws Exception {
        // Legacy geometry tests explicitly opt in to experimental modes.
        if (!modeId.equals("classic") && !modeId.equals("classic-powers"))
            jdbc.update("UPDATE game_mode_settings SET enabled=TRUE WHERE mode_id=?",modeId);
        String room=create(host,"{\"modeId\":\""+modeId+"\"}");
        String code=JsonPath.read(room,"$.code");
        mvc.perform(post("/api/matchmaking/lobbies/join")
            .header("Authorization","Bearer "+guest.jwt).contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\""+code+"\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.mode.id").value(modeId));
        for(Player player:new Player[]{host,guest}) {
            mvc.perform(put("/api/matchmaking/lobbies/me/ready")
                .header("Authorization","Bearer "+player.jwt)
                .contentType(MediaType.APPLICATION_JSON).content("{\"ready\":true}"))
                .andExpect(status().isOk());
        }
        return code;
    }
    private void leave(Player player) throws Exception {
        mvc.perform(delete("/api/matchmaking/lobbies/me")
            .header("Authorization","Bearer "+player.jwt)).andExpect(status().isNoContent());
    }
    private String state(Player player) throws Exception {
        return mvc.perform(get("/api/matches/me/placement")
            .header("Authorization","Bearer "+player.jwt)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    @Test void catalogRequiresJwtAndIncludesPlayableCellsAndSupportedRules() throws Exception {
        mvc.perform(get("/api/game-modes")).andExpect(status().isUnauthorized());
        Player user=player();
        mvc.perform(get("/api/game-modes").header("Authorization","Bearer "+user.jwt))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == 'classic')]").isNotEmpty())
            .andExpect(jsonPath("$[?(@.id == 'quick')]").isEmpty())
            .andExpect(jsonPath("$[?(@.id == 'triangular')]").isEmpty())
            .andExpect(jsonPath("$[?(@.id == 'triangle-tiles')]").isEmpty())
            .andExpect(jsonPath("$[?(@.id == 'hexagons')]").isEmpty())
            .andExpect(jsonPath("$[?(@.id == 'classic-powers')]").isNotEmpty());
    }

    @Test void defaultClassicIsBackwardCompatibleAndUnknownModeCannotAllocateRoom() throws Exception {
        Player host=player();
        mvc.perform(post("/api/matchmaking/lobbies")
            .header("Authorization","Bearer "+host.jwt)
            .contentType(MediaType.APPLICATION_JSON).content("{\"modeId\":\"not-registered\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/matchmaking/lobbies")
            .header("Authorization","Bearer "+host.jwt)
            .contentType(MediaType.APPLICATION_JSON).content("{\"modeId\":\"not-registered\",\"userId\":123}"))
            .andExpect(status().isBadRequest());
        String classic=create(host,null);
        assertEquals("classic", JsonPath.read(classic,"$.mode.id"));
        assertEquals(3,((Number)JsonPath.read(classic,"$.mode.fleet.length()")).intValue());
        assertEquals(64,((Number)JsonPath.read(classic,"$.mode.geometry.cells.length()")).intValue());
        leave(host);
    }

    @Test void bothBaseModesCanCreateSquareLobbiesAndOnlyPowersModeExposesAbilities() throws Exception {
        for(String id:new String[]{"classic","classic-powers"}) {
            Player host=player(),guest=player();
            try {
                prepare(host,guest,id);
                String status=state(host);
                assertEquals(id,JsonPath.read(status,"$.mode.id"));
                assertEquals(64,((Number)JsonPath.read(status,"$.mode.geometry.cells.length()")).intValue());
                assertEquals(3,((Number)JsonPath.read(status,"$.mode.fleet.length()")).intValue());
                for(Player participant:new Player[]{host,guest}) {
                    mvc.perform(post("/api/matches/me/placement/random")
                        .header("Authorization","Bearer "+participant.jwt))
                        .andExpect(status().isOk());
                    mvc.perform(post("/api/matches/me/placement/confirm")
                        .header("Authorization","Bearer "+participant.jwt))
                        .andExpect(status().isOk());
                }
                mvc.perform(get("/api/matches/me")
                    .header("Authorization","Bearer "+host.jwt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.yourAbilities.length()").value(id.equals("classic")?0:8));
            } finally {
                leave(host);
                leave(host);
                leave(guest);
            }
        }
    }

    @Test void quickModeHasDistinctFleetAndPrivateOpponentViews() throws Exception {
        Player a=player(),b=player();
        try {
            String code=prepare(a,b,"quick");
            assertEquals(8,((Number)JsonPath.read(state(a),"$.boardSize")).intValue());
            String payload="""
                {"ships":[
                  {"type":"BATTLESHIP","row":0,"col":0,"orientation":"HORIZONTAL"},
                  {"type":"CRUISER","row":1,"col":0,"orientation":"HORIZONTAL"},
                  {"type":"DESTROYER","row":2,"col":0,"orientation":"HORIZONTAL"}
                ]}
                """;
            String placed=mvc.perform(put("/api/matches/me/placement")
                .header("Authorization","Bearer "+a.jwt).contentType(MediaType.APPLICATION_JSON)
                .content(payload)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            assertEquals(code,JsonPath.read(placed,"$.lobbyCode"));
            assertEquals(3,((Number)JsonPath.read(placed,"$.yourShips.length()")).intValue());
            assertTrue(state(b).contains("\"opponentPlaced\":true"));
            assertEquals(0,((Number)JsonPath.read(state(b),"$.yourShips.length()")).intValue());
            assertEquals(2,((Number)JsonPath.read(state(b),
                "$.mode.placementDirections.length()")).intValue());

            // A classic-only type cannot be smuggled into the quick fleet.
            mvc.perform(put("/api/matches/me/placement")
                .header("Authorization","Bearer "+b.jwt).contentType(MediaType.APPLICATION_JSON)
                .content(payload.replace("BATTLESHIP","CARRIER"))).andExpect(status().isBadRequest());
            mvc.perform(post("/api/matches/me/placement/random")
                .header("Authorization","Bearer "+b.jwt)).andExpect(status().isOk())
                .andExpect(jsonPath("$.yourShips.length()").value(3));
            mvc.perform(post("/api/matches/me/placement/confirm")
                .header("Authorization","Bearer "+a.jwt)).andExpect(status().isOk());
            mvc.perform(post("/api/matches/me/placement/confirm")
                .header("Authorization","Bearer "+b.jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bothConfirmed").value(true));
        } finally { leave(a); }
    }

    @Test void triangularModeHonorsThePlayableMaskAndKeepsItsDefinitionAfterJoin() throws Exception {
        Player a=player(),b=player();
        try {
            prepare(a,b,"triangular");
            String status=state(a);
            assertEquals("TRIANGULAR_MASK",JsonPath.read(status,"$.mode.geometry.kind"));
            assertEquals(55,((Number)JsonPath.read(status,"$.mode.geometry.cells.length()")).intValue());
            String placed=mvc.perform(post("/api/matches/me/placement/random")
                .header("Authorization","Bearer "+a.jwt))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(3,((Number)JsonPath.read(placed,"$.yourShips.length()")).intValue());
            mvc.perform(put("/api/matches/me/placement")
                .header("Authorization","Bearer "+b.jwt).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ships":[
                      {"type":"BATTLESHIP","row":0,"col":0,"orientation":"HORIZONTAL"},
                      {"type":"CRUISER","row":1,"col":0,"orientation":"HORIZONTAL"},
                      {"type":"DESTROYER","row":2,"col":0,"orientation":"HORIZONTAL"}
                    ]}
                    """)).andExpect(status().isBadRequest());
            mvc.perform(post("/api/matches/me/placement/random")
                .header("Authorization","Bearer "+b.jwt))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode.id").value("triangular"));
        } finally { leave(a); }
    }
    @Test void trueTriangularTilesCanBeChosenPlacedAndPlayedThroughJwtOnly() throws Exception {
        Player host=player(), guest=player();
        try {
            prepare(host, guest, "triangle-tiles");
            String hostView=state(host);
            assertEquals("TRIANGULAR_TILES", JsonPath.read(hostView,"$.mode.geometry.kind"));
            assertEquals(64, ((Number)JsonPath.read(hostView,
                "$.mode.geometry.cells.length()")).intValue());
            assertEquals(64, ((Number)JsonPath.read(hostView,
                "$.mode.geometry.tiles.length()")).intValue());
            assertEquals("TRIANGLE_UP", JsonPath.read(hostView,
                "$.mode.geometry.tiles[0].shape"));
            assertEquals("TRI_RIGHT", JsonPath.read(hostView,
                "$.mode.placementDirections[0]"));
            String fleet="""
                {"ships":[
                  {"type":"BATTLESHIP","row":5,"col":0,"orientation":"TRI_RIGHT"},
                  {"type":"CRUISER","row":1,"col":0,"orientation":"TRI_DOWN_RIGHT"},
                  {"type":"DESTROYER","row":3,"col":0,"orientation":"TRI_DOWN_LEFT"}
                ]}
                """;
            mvc.perform(put("/api/matches/me/placement")
                .header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON).content(fleet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yourShips.length()").value(3));
            assertEquals(0,((Number)JsonPath.read(state(guest),
                "$.yourShips.length()")).intValue());
            mvc.perform(put("/api/matches/me/placement")
                .header("Authorization","Bearer "+guest.jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fleet.replace("TRI_RIGHT","HORIZONTAL")))
                .andExpect(status().isBadRequest());
            mvc.perform(post("/api/matches/me/placement/random")
                .header("Authorization","Bearer "+guest.jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yourShips.length()").value(3));
            for (Player player : new Player[]{host,guest}) {
                mvc.perform(post("/api/matches/me/placement/confirm")
                    .header("Authorization","Bearer "+player.jwt))
                    .andExpect(status().isOk());
            }
            mvc.perform(post("/api/matches/me/shots")
                .header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"row":1,"col":3}
                    """))
                .andExpect(status().isBadRequest());
            mvc.perform(post("/api/matches/me/shots")
                .header("Authorization","Bearer "+host.jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"row":0,"col":0}
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shotsFired.length()").value(1))
                .andExpect(jsonPath("$.mode.geometry.kind").value("TRIANGULAR_TILES"));
        } finally {
            leave(host);
            leave(host);
            leave(guest);
        }
    }

    @Test void hexagonalModeWorksFromLobbyToBattleWithJwt() throws Exception {
        for(String modeId : new String[]{"hexagons"}) {
            Player host=player(),guest=player();
            try {
                prepare(host,guest,modeId);
                String own=state(host),other=state(guest);
                String kind="HEXAGONAL_TILES";
                int cells=61;
                int axes=3;
                assertEquals(kind,JsonPath.read(own,"$.mode.geometry.kind"));
                assertEquals(cells,((Number)JsonPath.read(own,
                    "$.mode.geometry.cells.length()")).intValue());
                assertEquals(cells,((Number)JsonPath.read(own,
                    "$.mode.geometry.tiles.length()")).intValue());
                assertEquals(axes,((Number)JsonPath.read(own,
                    "$.mode.placementDirections.length()")).intValue());

                // Unknown mode-specific orientation must fail before editing a fleet.
                String invalid="HORIZONTAL";
                mvc.perform(put("/api/matches/me/placement")
                    .header("Authorization","Bearer "+host.jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"ships":[
                          {"type":"BATTLESHIP","row":4,"col":4,"orientation":"%s"},
                          {"type":"CRUISER","row":4,"col":0,"orientation":"%s"},
                          {"type":"DESTROYER","row":6,"col":1,"orientation":"%s"}
                        ]}
                        """.formatted(invalid,invalid,invalid)))
                    .andExpect(status().isBadRequest());

                mvc.perform(post("/api/matches/me/placement/random")
                    .header("Authorization","Bearer "+host.jwt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.yourShips.length()").value(3));
                mvc.perform(post("/api/matches/me/placement/random")
                    .header("Authorization","Bearer "+guest.jwt))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.yourShips.length()").value(3));
                assertEquals(3,((Number)JsonPath.read(state(guest),
                    "$.yourShips.length()")).intValue());
                assertEquals(0,((Number)JsonPath.read(own,
                    "$.yourShips.length()")).intValue());
                assertEquals(true,JsonPath.read(state(guest),"$.opponentPlaced"));
                for(Player p:new Player[]{host,guest}) {
                    mvc.perform(post("/api/matches/me/placement/confirm")
                        .header("Authorization","Bearer "+p.jwt))
                        .andExpect(status().isOk());
                }
                if(modeId.equals("hexagons")) {
                    mvc.perform(post("/api/matches/me/shots")
                        .header("Authorization","Bearer "+host.jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"row":0,"col":0}
                            """)).andExpect(status().isBadRequest());
                }
                mvc.perform(post("/api/matches/me/shots")
                    .header("Authorization","Bearer "+host.jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"row":4,"col":4}
                        """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode.geometry.kind").value(kind))
                    .andExpect(jsonPath("$.shotsFired.length()").value(1));
            } finally {
                // PLAYING -> FINISHED forfeit -> dismiss. Also safe before PLAYING.
                leave(host);
                leave(host);
                leave(guest);
            }
        }
    }

}
