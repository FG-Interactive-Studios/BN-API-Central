package com.FGInteractive.BatalhaNaval.store.service;

import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Administrative sale switch, independent from release readiness and ownership. */
@Service
public class AdminStoreService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public AdminStoreService(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc=jdbc;
        this.users=users;
    }

    public record Item(String id, String category, String name, int price, boolean premiumOnly,
                       boolean released, boolean enabled, boolean availableForPurchase,
                       boolean manageable, Instant updatedAt, Long updatedBy) {}

    private static final String SELECT = """
        SELECT i.id,i.category,i.name,i.price,i.premium_only,i.enabled,i.sale_enabled,
               i.sale_updated_at,i.sale_updated_by,
               (i.enabled AND i.sale_enabled AND (i.category <> 'BUNDLE' OR (
                   EXISTS (SELECT 1 FROM store_bundle_components bc WHERE bc.bundle_id=i.id)
                   AND NOT EXISTS (SELECT 1 FROM store_bundle_components bc JOIN store_items child ON child.id=bc.item_id
                       WHERE bc.bundle_id=i.id AND (NOT child.enabled OR NOT child.sale_enabled))))) AS available
          FROM store_items i
        """;

    private void requireAdmin(long actor) {
        if (!users.existsByIdAndAdminTrue(actor))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Acesso restrito a administradores.");
    }

    private List<Item> query(String suffix, Object... args) {
        return jdbc.query(SELECT+suffix,(rs,row)->{
            var timestamp=rs.getTimestamp("sale_updated_at");
            boolean released=rs.getBoolean("enabled");
            int price=rs.getInt("price");
            return new Item(rs.getString("id"),rs.getString("category"),rs.getString("name"),
                price,rs.getBoolean("premium_only"),released,released&&rs.getBoolean("sale_enabled"),
                rs.getBoolean("available"),released&&price>0,
                timestamp==null?null:timestamp.toInstant(),rs.getObject("sale_updated_by",Long.class));
        },args);
    }

    @Transactional(readOnly=true)
    public List<Item> list(long actor) {
        requireAdmin(actor);
        return query(" ORDER BY i.display_order,i.id");
    }

    @Transactional
    public Item change(long actor,String id,Boolean enabled) {
        requireAdmin(actor);
        if(enabled==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"enabled deve ser booleano.");
        jdbc.execute("SELECT pg_advisory_xact_lock(702211261)");
        var rows=query(" WHERE i.id=?",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Item não encontrado.");
        Item current=rows.getFirst();
        if(!current.manageable())throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Itens gratuitos padrão ou não homologados não podem ser alterados.");
        if(current.enabled()!=enabled)jdbc.update("""
            UPDATE store_items SET sale_enabled=?,sale_updated_at=CURRENT_TIMESTAMP,sale_updated_by=? WHERE id=?
            """,enabled,actor,id);
        return query(" WHERE i.id=?",id).getFirst();
    }
}
