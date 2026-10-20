package com.FGInteractive.BatalhaNaval.store;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class StoreCosmeticsPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private record Player(long id, String token) {}

    private Player player() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "store-" + suffix + "@example.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Shop%s","email":"%s","password":"safe-password-123"}
                """.formatted(suffix, email))).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"safe-password-123"}
                """.formatted(email))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Player(((Number)JsonPath.read(login, "$.user.id")).longValue(),
            JsonPath.read(login, "$.accessToken"));
    }

    private String store(Player p) throws Exception {
        return mvc.perform(get("/api/store")
            .header("Authorization", "Bearer " + p.token()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String purchase(Player p, String item, int expected) throws Exception {
        return mvc.perform(post("/api/store/purchase/{id}",item)
            .header("Authorization","Bearer "+p.token()))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private String equip(Player p, String item, int expected) throws Exception {
        return mvc.perform(put("/api/store/equip/{id}",item)
            .header("Authorization","Bearer "+p.token()))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void anonymousCannotReadOrModifyStore() throws Exception {
        mvc.perform(get("/api/store")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/store/purchase/skin_coral")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/store/equip/skin_coral")).andExpect(status().isUnauthorized());
    }

    @Test
    void defaultsAreOwnedAndNoWalletResetsOnRepeatedReads() throws Exception {
        Player p=player();
        String first=store(p);
        assertEquals(300, ((Number) JsonPath.read(first,"$.credits")).intValue());
        assertEquals(true,JsonPath.read(first,"$.items[?(@.id == 'skin_default')].owned").toString().contains("true"));
        assertEquals(true,JsonPath.read(first,"$.items[?(@.id == 'sound_default')].equipped").toString().contains("true"));
        assertEquals(false,JsonPath.read(first,"$.items[?(@.id == 'sound_sonar')].enabled").toString().contains("true"));
        purchase(p,"skin_coral",200);
        store(p);
        assertEquals(210, ((Number)jdbc.queryForObject(
            "SELECT credits FROM player_wallets WHERE user_id = ?", Integer.class,p.id())).intValue());
    }

    @Test
    void purchaseAndEquipRespectInventoryAndDoNotSpendTwice() throws Exception {
        Player p=player();
        equip(p,"skin_coral",409);
        String bought=purchase(p,"skin_coral",200);
        assertEquals(210,((Number)JsonPath.read(bought,"$.credits")).intValue());
        String duplicate=purchase(p,"skin_coral",409);
        assertEquals("ALREADY_OWNED",JsonPath.read(duplicate,"$.code"));
        String equipped=equip(p,"skin_coral",200);
        assertEquals(true, JsonPath.read(equipped,"$.items[?(@.id == 'skin_coral')].equipped")
            .toString().contains("true"));
        assertEquals(false, JsonPath.read(equipped,"$.items[?(@.id == 'skin_default')].equipped")
            .toString().contains("true"));
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM player_items WHERE user_id=? AND item_id='skin_coral'",
            Integer.class,p.id()));
        assertEquals(210, jdbc.queryForObject(
            "SELECT credits FROM player_wallets WHERE user_id=?", Integer.class,p.id()));
    }

    @Test
    void disabledAndUnknownItemsCannotBePurchasedOrEquipped() throws Exception {
        Player p=player();
        assertEquals("ITEM_UNAVAILABLE",JsonPath.read(purchase(p,"sound_sonar",409),"$.code"));
        assertEquals("ITEM_NOT_FOUND",JsonPath.read(purchase(p,"does-not-exist",404),"$.code"));
        assertEquals("NOT_OWNED",JsonPath.read(equip(p,"skin_abyss",409),"$.code"));
        assertEquals(300, JsonPath.read(store(p),"$.credits"));
    }

    @Test
    void priceIsServerControlledAndOtherAccountsCannotReadInventory() throws Exception {
        Player a=player(), b=player();
        purchase(a,"skin_abyss",200);
        assertEquals(160,JsonPath.read(store(a),"$.credits"));
        assertEquals(300,JsonPath.read(store(b),"$.credits"));
        assertEquals(false,JsonPath.read(store(b),"$.items[?(@.id == 'skin_abyss')].owned").toString().contains("true"));
        // The endpoint ignores request payload fields that pretend to set a price.
        mvc.perform(post("/api/store/purchase/skin_coral")
                .header("Authorization","Bearer "+a.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":0,\"userId\":"+b.id()+"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.credits").value(70));
        assertEquals(300,JsonPath.read(store(b),"$.credits"));
        assertEquals(2,jdbc.queryForObject(
            "SELECT COUNT(*) FROM player_items WHERE user_id=? AND item_id IN ('skin_coral','skin_abyss')",
            Integer.class,a.id()));
    }

    @Test
    void insufficientCreditsNeverCreatesOrEquipsAnItem() throws Exception {
        Player p=player();
        jdbc.update("INSERT INTO player_wallets(user_id,credits) VALUES (?, 50)",p.id());
        String error=purchase(p,"skin_abyss",409);
        assertEquals("INSUFFICIENT_CREDITS",JsonPath.read(error,"$.code"));
        assertEquals(50,JsonPath.read(store(p),"$.credits"));
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(*) FROM player_items WHERE user_id=? AND item_id='skin_abyss'",
            Integer.class,p.id()));
    }
}
