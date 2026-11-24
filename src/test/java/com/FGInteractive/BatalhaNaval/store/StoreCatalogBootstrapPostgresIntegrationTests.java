package com.FGInteractive.BatalhaNaval.store;

import static org.junit.jupiter.api.Assertions.*;

import com.FGInteractive.BatalhaNaval.store.service.StoreCatalogBootstrapService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Flyway V7 -> V8 -> V9 plus the API startup runner, on real PostgreSQL. */
@SpringBootTest
@ActiveProfiles("ci")
class StoreCatalogBootstrapPostgresIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired StoreCatalogBootstrapService catalog;

    @Test
    void firstStartupRegistersApprovedLegacyAndDraftProductsWithoutSellingDrafts() {
        for(String legacy: List.of("skin_default","skin_coral","skin_abyss",
                "sound_default","sound_sonar","sound_deep")) {
            assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM store_items WHERE id=?",Integer.class,legacy));
            assertEquals(1,jdbc.queryForObject(
                "SELECT applied_revision FROM store_catalog_managed WHERE item_id=?",
                Integer.class,legacy));
        }
        for(String staged: List.of("skin_corsair","skin_space","skin_pirate",
                "skin_magnate","captain_default","captain_corsair",
                "captain_space","bundle_corsair","bundle_space")) {
            assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM store_items WHERE id=? AND enabled=FALSE AND price=0",
                Integer.class,staged),staged);
            assertEquals(1,jdbc.queryForObject(
                "SELECT applied_revision FROM store_catalog_managed WHERE item_id=?",
                Integer.class,staged));
        }
        assertEquals(40,jdbc.queryForObject(
            "SELECT COUNT(*) FROM store_catalog_managed",Integer.class));
        assertEquals(38,jdbc.queryForObject(
            "SELECT COUNT(*) FROM store_items WHERE enabled=TRUE",Integer.class));
        assertEquals(2,jdbc.queryForObject(
            "SELECT COUNT(*) FROM store_bundle_components WHERE bundle_id='bundle_corsair'",
            Integer.class));
        assertEquals(2,jdbc.queryForObject(
            "SELECT COUNT(*) FROM store_bundle_components WHERE bundle_id='bundle_space'",
            Integer.class));
        assertEquals("CAPTAIN",jdbc.queryForObject(
            "SELECT category FROM store_items WHERE id='captain_corsair'",String.class));
        assertEquals("BUNDLE",jdbc.queryForObject(
            "SELECT category FROM store_items WHERE id='bundle_corsair'",String.class));
    }

    @Test
    @Transactional
    void restartIsIdempotentPreservesReviewedPricesAndDisablesAccidentalDraftActivation() {
        jdbc.update("UPDATE store_items SET price=777 WHERE id='skin_coral'");
        jdbc.update("UPDATE store_items SET enabled=TRUE WHERE id='sound_sonar'");
        jdbc.update("UPDATE store_items SET price=913 WHERE id='skin_space'");
        catalog.synchronize();
        catalog.synchronize();
        assertEquals(777,jdbc.queryForObject(
            "SELECT price FROM store_items WHERE id='skin_coral'",Integer.class));
        assertEquals(913,jdbc.queryForObject(
            "SELECT price FROM store_items WHERE id='skin_space'",Integer.class));
        assertEquals(false,jdbc.queryForObject(
            "SELECT enabled FROM store_items WHERE id='sound_sonar'",Boolean.class));
        assertEquals(40,jdbc.queryForObject(
            "SELECT COUNT(*) FROM store_catalog_managed",Integer.class));
        assertEquals(7,jdbc.queryForObject(
            "SELECT COUNT(*) FROM store_bundle_components",Integer.class));
    }

    @Test
    @Transactional
    void olderDeploymentDoesNotRollBackNewerReviewedCatalogRevision() {
        jdbc.update("""
            UPDATE store_catalog_managed SET applied_revision=2 WHERE item_id='skin_coral'
            """);
        jdbc.update("UPDATE store_items SET price=250 WHERE id='skin_coral'");
        catalog.synchronize(); // bundled JSON is revision 1, so it cannot revert.
        assertEquals(250,jdbc.queryForObject(
            "SELECT price FROM store_items WHERE id='skin_coral'",Integer.class));
        assertEquals(2,jdbc.queryForObject(
            "SELECT applied_revision FROM store_catalog_managed WHERE item_id='skin_coral'",
            Integer.class));
    }

    @Test
    void stagedBundlesNeverReferenceUncataloguedProducts() {
        List<String> components=jdbc.queryForList("""
            SELECT item_id FROM store_bundle_components WHERE bundle_id='bundle_corsair'
            ORDER BY item_id
            """,String.class);
        assertEquals(List.of("captain_corsair","skin_corsair"),components);
        List<String> future=jdbc.queryForList("""
            SELECT item_id FROM store_bundle_components WHERE bundle_id='bundle_space'
            ORDER BY item_id
            """,String.class);
        assertEquals(List.of("captain_space","skin_space"),future);
    }
}
