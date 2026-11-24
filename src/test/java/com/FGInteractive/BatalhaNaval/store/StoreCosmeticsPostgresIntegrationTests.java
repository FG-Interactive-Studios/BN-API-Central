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
    @Autowired com.FGInteractive.BatalhaNaval.store.service.StoreCatalogBootstrapService bootstrap;

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

    private void availability(Player p,String id,boolean enabled,int statusCode) throws Exception {
        mvc.perform(put("/api/admin/store/items/{id}/availability",id)
            .header("Authorization","Bearer "+p.token()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":"+enabled+"}")).andExpect(status().is(statusCode));
    }

    @Test
    void adminSaleSwitchPreservesOwnershipAndSurvivesCatalogSync() throws Exception {
        Player admin=player(),owner=player(),buyer=player();
        mvc.perform(get("/api/admin/store/items")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/store/items").header("Authorization","Bearer "+buyer.token()))
            .andExpect(status().isForbidden());
        availability(buyer,"sound_pirate",false,403);
        jdbc.update("UPDATE users SET is_admin=TRUE WHERE id=?",admin.id());
        mvc.perform(get("/api/admin/store/items").header("Authorization","Bearer "+admin.token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == 'sound_pirate')]").isNotEmpty());
        availability(admin,"unknown_item",false,404);
        availability(admin,"sound_default",false,409);
        availability(admin,"sound_deep",true,409);
        purchase(owner,"sound_pirate",200);
        try {
            availability(admin,"sound_pirate",false,200);
            bootstrap.synchronize();
            assertEquals(false,jdbc.queryForObject("SELECT sale_enabled FROM store_items WHERE id='sound_pirate'",Boolean.class));
            assertEquals(admin.id(),jdbc.queryForObject("SELECT sale_updated_by FROM store_items WHERE id='sound_pirate'",Long.class).longValue());
            assertEquals("ITEM_UNAVAILABLE",JsonPath.read(purchase(buyer,"sound_pirate",409),"$.code"));
            assertEquals(300,((Number)JsonPath.read(store(buyer),"$.credits")).intValue());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_items WHERE user_id=? AND item_id='sound_pirate'",Integer.class,owner.id()));
            equip(owner,"sound_pirate",200);
            availability(admin,"skin_abyss",false,200);
            assertEquals("ITEM_UNAVAILABLE",JsonPath.read(purchase(buyer,"bundle_chaos",409),"$.code"));
        } finally {
            availability(admin,"skin_abyss",true,200);
            availability(admin,"sound_pirate",true,200);
        }
        purchase(buyer,"sound_pirate",200);
        jdbc.update("UPDATE users SET is_admin=FALSE WHERE id=?",admin.id());
        availability(admin,"sound_pirate",false,403);
    }

    @Test
    void simulatedPremiumTopupAndPurchaseArePersistentAndIdempotent() throws Exception {
        Player p=player();
        UUID requestId=UUID.randomUUID();
        String body="{\"packId\":\"demo_100\",\"requestId\":\""+requestId+"\"}";
        for(int i=0;i<2;i++) {
            mvc.perform(post("/api/store/demo-premium").header("Authorization","Bearer "+p.token())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.premiumCredits").value(100))
                .andExpect(jsonPath("$.credits").value(300));
        }
        mvc.perform(post("/api/store/purchase/skin_coral").param("premium","true")
            .header("Authorization","Bearer "+p.token())).andExpect(status().isOk())
            .andExpect(jsonPath("$.premiumCredits").value(70))
            .andExpect(jsonPath("$.credits").value(300));
        mvc.perform(post("/api/store/purchase/skin_coral").param("premium","true")
            .header("Authorization","Bearer "+p.token())).andExpect(status().isConflict());
        assertEquals(70,((Number)JsonPath.read(store(p),"$.premiumCredits")).intValue());
        mvc.perform(post("/api/store/demo-premium").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void bundleGrantsAllComponentsOnceAndDoesNotEquipTheBundle() throws Exception {
        Player p=player();
        String body="{\"packId\":\"demo_300\",\"requestId\":\""+UUID.randomUUID()+"\"}";
        mvc.perform(post("/api/store/demo-premium").header("Authorization","Bearer "+p.token())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/store/purchase/bundle_chaos").param("premium","true")
            .header("Authorization","Bearer "+p.token())).andExpect(status().isOk())
            .andExpect(jsonPath("$.premiumCredits").value(190));
        for(String id:new String[]{"skin_abyss","taunt_support","taunt_meme","bundle_chaos"}) {
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_items WHERE user_id=? AND item_id=?",
                Integer.class,p.id(),id));
        }
        assertEquals("ITEM_UNAVAILABLE",JsonPath.read(equip(p,"bundle_chaos",409),"$.code"));
        assertEquals("ALREADY_OWNED",JsonPath.read(purchase(p,"bundle_chaos",409),"$.code"));
        assertEquals(190,((Number)JsonPath.read(store(p),"$.premiumCredits")).intValue());
    }

    @Test
    void expressiveCosmeticsAreOwnedBeforeEquipAndPublicPresentationIsSafe() throws Exception {
        Player p=player();
        assertEquals("NOT_OWNED",JsonPath.read(equip(p,"attack_clown",409),"$.code"));
        purchase(p,"attack_clown",200);
        equip(p,"attack_clown",200);
        mvc.perform(post("/api/matchmaking/lobbies").header("Authorization","Bearer "+p.token())
            .contentType(MediaType.APPLICATION_JSON).content("{\"modeId\":\"classic\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.players[0].cosmetics.ATTACK").value("attack_clown"))
            .andExpect(jsonPath("$.players[0].cosmetics.credits").doesNotExist());
        mvc.perform(delete("/api/matchmaking/lobbies/me").header("Authorization","Bearer "+p.token()))
            .andExpect(status().isNoContent());
    }

    @Test
    void officialCaptainUsesNavalCoinsAndRequiresOwnership() throws Exception {
        Player p=player();
        assertEquals("PREMIUM_REQUIRED",JsonPath.read(purchase(p,"captain_pirate",409),"$.code"));
        assertEquals("NOT_OWNED",JsonPath.read(equip(p,"captain_pirate",409),"$.code"));
        String body="{\"packId\":\"demo_300\",\"requestId\":\""+UUID.randomUUID()+"\"}";
        mvc.perform(post("/api/store/demo-premium").header("Authorization","Bearer "+p.token())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/store/purchase/captain_pirate").param("premium","true")
            .header("Authorization","Bearer "+p.token())).andExpect(status().isOk())
            .andExpect(jsonPath("$.premiumCredits").value(100)).andExpect(jsonPath("$.credits").value(300));
        equip(p,"captain_pirate",200);
        mvc.perform(post("/api/matchmaking/lobbies").header("Authorization","Bearer "+p.token())
            .contentType(MediaType.APPLICATION_JSON).content("{\"modeId\":\"classic\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.players[0].cosmetics.CAPTAIN").value("captain_pirate"));
        mvc.perform(delete("/api/matchmaking/lobbies/me").header("Authorization","Bearer "+p.token()))
            .andExpect(status().isNoContent());
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
        assertEquals(0, ((Number) JsonPath.read(first,"$.premiumCredits")).intValue());
        assertEquals(true,JsonPath.read(first,"$.items[?(@.id == 'skin_default')].owned").toString().contains("true"));
        assertEquals(true,JsonPath.read(first,"$.items[?(@.id == 'sound_default')].equipped").toString().contains("true"));
        assertEquals(true,JsonPath.read(first,"$.items[?(@.id == 'sound_sonar')].enabled").toString().contains("true"));
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
        assertEquals(0,((Number)JsonPath.read(bought,"$.premiumCredits")).intValue());
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
    void retroPackCanBePurchasedEquippedAndReplacedWithDefault() throws Exception {
        Player p=player();
        String bought=purchase(p,"sound_sonar",200);
        assertEquals(210,((Number)JsonPath.read(bought,"$.credits")).intValue());
        assertEquals("ALREADY_OWNED",JsonPath.read(purchase(p,"sound_sonar",409),"$.code"));
        String equipped=equip(p,"sound_sonar",200);
        assertEquals(true,JsonPath.read(equipped,"$.items[?(@.id == 'sound_sonar')].equipped").toString().contains("true"));
        assertEquals(false,JsonPath.read(equipped,"$.items[?(@.id == 'sound_default')].equipped").toString().contains("true"));
        String restored=equip(p,"sound_default",200);
        assertEquals(true,JsonPath.read(restored,"$.items[?(@.id == 'sound_default')].equipped").toString().contains("true"));
        assertEquals(210,((Number)JsonPath.read(restored,"$.credits")).intValue());
    }

    @Test
    void pirateSoundPackRequiresOwnershipAndCanBeEquipped() throws Exception {
        Player p=player();
        assertEquals("NOT_OWNED",JsonPath.read(equip(p,"sound_pirate",409),"$.code"));
        String bought=purchase(p,"sound_pirate",200);
        assertEquals(160,((Number)JsonPath.read(bought,"$.credits")).intValue());
        assertEquals("ALREADY_OWNED",JsonPath.read(purchase(p,"sound_pirate",409),"$.code"));
        String equipped=equip(p,"sound_pirate",200);
        assertTrue(JsonPath.read(equipped,"$.items[?(@.id == 'sound_pirate')].equipped").toString().contains("true"));
        assertFalse(JsonPath.read(equipped,"$.items[?(@.id == 'sound_default')].equipped").toString().contains("true"));
        String restored=equip(p,"sound_default",200);
        assertTrue(JsonPath.read(restored,"$.items[?(@.id == 'sound_default')].equipped").toString().contains("true"));
        assertEquals(160,((Number)JsonPath.read(restored,"$.credits")).intValue());
    }

    @Test
    void disabledAndUnknownItemsCannotBePurchasedOrEquipped() throws Exception {
        Player p=player();
        assertEquals("ITEM_UNAVAILABLE",JsonPath.read(purchase(p,"sound_deep",409),"$.code"));
        assertEquals("PREMIUM_REQUIRED",JsonPath.read(purchase(p,"skin_corsair",409),"$.code"));
        assertEquals("PREMIUM_REQUIRED",JsonPath.read(purchase(p,"captain_corsair",409),"$.code"));
        assertEquals("PREMIUM_REQUIRED",JsonPath.read(purchase(p,"bundle_corsair",409),"$.code"));
        assertEquals("ITEM_UNAVAILABLE",JsonPath.read(equip(p,"bundle_corsair",409),"$.code"));
        assertEquals("ITEM_NOT_FOUND",JsonPath.read(purchase(p,"does-not-exist",404),"$.code"));
        assertEquals("NOT_OWNED",JsonPath.read(equip(p,"skin_abyss",409),"$.code"));
        assertEquals(300, ((Number)JsonPath.read(store(p),"$.credits")).intValue());
    }

    @Test
    void priceIsServerControlledAndOtherAccountsCannotReadInventory() throws Exception {
        Player a=player(), b=player();
        purchase(a,"skin_abyss",200);
        assertEquals(160,((Number)JsonPath.read(store(a),"$.credits")).intValue());
        assertEquals(300,((Number)JsonPath.read(store(b),"$.credits")).intValue());
        assertEquals(false,JsonPath.read(store(b),"$.items[?(@.id == 'skin_abyss')].owned").toString().contains("true"));
        // The endpoint ignores request payload fields that pretend to set a price.
        mvc.perform(post("/api/store/purchase/skin_coral")
                .header("Authorization","Bearer "+a.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":0,\"userId\":"+b.id()+"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.credits").value(70));
        assertEquals(300,((Number)JsonPath.read(store(b),"$.credits")).intValue());
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
        assertEquals(50,((Number)JsonPath.read(store(p),"$.credits")).intValue());
        assertEquals(0,jdbc.queryForObject(
            "SELECT COUNT(*) FROM player_items WHERE user_id=? AND item_id='skin_abyss'",
            Integer.class,p.id()));
    }
}
