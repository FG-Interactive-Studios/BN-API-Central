package com.FGInteractive.BatalhaNaval.store.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API-owned, reviewed catalog. The frontend asset manifest never sets prices,
 * entitlements or availability. Called once per start after Flyway migrations.
 */
@Service
public class StoreCatalogBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(StoreCatalogBootstrapService.class);
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]{1,39}");
    private static final Set<String> CATEGORIES = Set.of("SKIN","SOUND","CAPTAIN","BUNDLE","TAUNT");
    private static final Set<String> STATUSES = Set.of("DRAFT","RELEASED");
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public StoreCatalogBootstrapService(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    record CatalogItem(String id, String category, String name, String description,
                       int price, int order, int revision, boolean enabled,
                       boolean draft, List<String> components) {}

    private static JsonNode require(JsonNode parent, String key) {
        JsonNode value = parent.get(key);
        if (value == null || value.isNull())
            throw new IllegalStateException("Missing catalog field: "+key);
        return value;
    }

    private static String string(JsonNode item, String field, int max) {
        JsonNode value = require(item,field);
        if (!value.isTextual()) throw new IllegalStateException("Catalog field must be text: "+field);
        String text = value.asText();
        if (text.isBlank() || text.length()>max || !text.equals(text.trim()))
            throw new IllegalStateException("Invalid catalog field: "+field);
        return text;
    }

    private static int number(JsonNode item, String field) {
        JsonNode value=require(item,field);
        if (!value.isIntegralNumber()) throw new IllegalStateException("Catalog integer: "+field);
        int v=value.asInt();
        if (v<0) throw new IllegalStateException("Negative catalog integer: "+field);
        return v;
    }

    private static boolean flag(JsonNode item, String field) {
        JsonNode value=require(item,field);
        if(!value.isBoolean())throw new IllegalStateException("Catalog boolean: "+field);
        return value.asBoolean();
    }

    /** Validate the entire file before the first database write. */
    List<CatalogItem> load() {
        JsonNode root;
        try {
            try (var input = new ClassPathResource("store/catalog-v1.json").getInputStream()) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                root=mapper.readTree(json);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read versioned store catalog",e);
        }
        if (root==null || number(root,"schemaVersion")!=1)
            throw new IllegalStateException("Unsupported store catalog schema");
        JsonNode entries=require(root,"items");
        if(!entries.isArray() || entries.isEmpty())
            throw new IllegalStateException("Store catalog must contain items");
        List<CatalogItem> result=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        for(JsonNode entry:entries) {
            String id=string(entry,"id",40);
            if(!ID.matcher(id).matches() || !ids.add(id))
                throw new IllegalStateException("Invalid or duplicate product ID: "+id);
            String category=string(entry,"category",16);
            if(!CATEGORIES.contains(category))
                throw new IllegalStateException("Unsupported product category: "+category);
            String status=string(entry,"status",16);
            if(!STATUSES.contains(status))
                throw new IllegalStateException("Unsupported product status: "+status);
            int price=number(entry,"price");
            int order=number(entry,"displayOrder");
            int revision=number(entry,"revision");
            if(revision<1) throw new IllegalStateException("Product revision must be positive");
            boolean assets=flag(entry,"assetsVerified");
            boolean pricing=flag(entry,"priceApproved");
            boolean approved=flag(entry,"approvedForSale");
            boolean draft=status.equals("DRAFT");
            if (!draft && (!assets || !pricing || !approved))
                throw new IllegalStateException("Cannot release unapproved product: "+id);
            // Captain entitlement and bundle fulfillment are not implemented.
            if (!draft && (category.equals("CAPTAIN") || category.equals("BUNDLE")))
                throw new IllegalStateException("Unsupported category released: "+id);
            JsonNode components=require(entry,"components");
            if(!components.isArray())throw new IllegalStateException("Invalid bundle components: "+id);
            List<String> links=new ArrayList<>();
            for(JsonNode component:components) {
                if(!component.isTextual())throw new IllegalStateException("Invalid bundle component: "+id);
                String target=component.asText();
                if(!ID.matcher(target).matches()||links.contains(target)||target.equals(id))
                    throw new IllegalStateException("Invalid bundle component reference: "+id);
                links.add(target);
            }
            if(category.equals("BUNDLE")!=(!links.isEmpty()))
                throw new IllegalStateException("Only bundles must define components: "+id);
            result.add(new CatalogItem(id,category,string(entry,"name",80),
                string(entry,"description",255),price,order,revision,
                !draft, draft,List.copyOf(links)));
        }
        Map<String,CatalogItem> byId=new HashMap<>();
        result.forEach(item->byId.put(item.id(),item));
        for(CatalogItem item:result)for(String target:item.components()) {
            CatalogItem referenced=byId.get(target);
            if(referenced==null || referenced.category().equals("BUNDLE"))
                throw new IllegalStateException("Bundle references missing or nested item: "+target);
        }
        return List.copyOf(result);
    }

    /**
     * All writes are atomic. A DB advisory lock prevents multi-instance startup
     * from interleaving catalog revisions. V7 existing entries are only adopted;
     * equal revisions never overwrite owner-managed prices or purchased rights.
     */
    @Transactional
    public void synchronize() {
        List<CatalogItem> catalog=load();
        jdbc.execute("SELECT pg_advisory_xact_lock(702211261)");
        int created=0, updated=0;
        for(CatalogItem item:catalog) {
            int inserted=jdbc.update("""
                INSERT INTO store_items (id,category,name,description,price,enabled,display_order)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (id) DO NOTHING
                """,item.id(),item.category(),item.name(),item.description(),
                item.price(),item.enabled(),item.order());
            created+=inserted;
            String existingCategory=jdbc.queryForObject(
                "SELECT category FROM store_items WHERE id=?",String.class,item.id());
            if(!item.category().equals(existingCategory))
                throw new IllegalStateException("Product ID category cannot change: "+item.id());

            jdbc.update("""
                INSERT INTO store_catalog_managed(item_id,applied_revision)
                VALUES (?,?) ON CONFLICT (item_id) DO NOTHING
                """,item.id(),item.revision());
            int revision=jdbc.queryForObject("""
                SELECT applied_revision FROM store_catalog_managed
                WHERE item_id=? FOR UPDATE
                """,Integer.class,item.id());
            if(revision<item.revision()) {
                jdbc.update("""
                    UPDATE store_items SET name=?,description=?,price=?,enabled=?,display_order=?
                    WHERE id=?
                    """,item.name(),item.description(),item.price(),
                    item.enabled(),item.order(),item.id());
                jdbc.update("""
                    UPDATE store_catalog_managed SET applied_revision=? WHERE item_id=?
                    """,item.revision(),item.id());
                updated++;
            }
            // No partial content becomes purchasable, even if a human accidentally
            // enabled a DRAFT directly in the database. Avoid reverting a newer
            // catalog revision if an older server binary is deployed.
            if(item.draft() && revision<=item.revision())
                jdbc.update("UPDATE store_items SET enabled=FALSE WHERE id=? AND enabled=TRUE",item.id());

            if(item.category().equals("BUNDLE") && revision<=item.revision()) {
                if(revision<item.revision())
                    jdbc.update("DELETE FROM store_bundle_components WHERE bundle_id=?",item.id());
                for(String component:item.components())
                    jdbc.update("""
                        INSERT INTO store_bundle_components(bundle_id,item_id)
                        VALUES (?,?) ON CONFLICT DO NOTHING
                        """,item.id(),component);
            }
        }
        log.info("Store catalog synchronized ({} items, {} inserted, {} revised; purchases unchanged)",
            catalog.size(),created,updated);
    }
}
