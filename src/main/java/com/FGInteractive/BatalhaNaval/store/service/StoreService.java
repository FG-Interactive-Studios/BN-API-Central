package com.FGInteractive.BatalhaNaval.store.service;

import com.FGInteractive.BatalhaNaval.store.dto.StoreDTO;
import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoreService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public StoreService(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    // Provisioning must be safe to repeat and must NOT reset an existing wallet.
    private void provision(long playerId) {
        if (!users.existsById(playerId)) {
            throw new StoreException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "Jogador não encontrado.");
        }
        jdbc.update("""
            INSERT INTO player_wallets (user_id, credits)
            VALUES (?, 300) ON CONFLICT (user_id) DO NOTHING
            """, playerId);
        jdbc.update("""
            INSERT INTO player_items (user_id, item_id)
            SELECT ?, id FROM store_items WHERE enabled = TRUE AND price = 0
            ON CONFLICT DO NOTHING
            """, playerId);
        jdbc.update("""
            INSERT INTO player_equipment (user_id, category, item_id)
            SELECT ?, category, id FROM store_items WHERE enabled = TRUE AND price = 0
            ON CONFLICT (user_id, category) DO NOTHING
            """, playerId);
    }

    private int lockedCredits(long playerId) {
        return jdbc.queryForObject(
            "SELECT credits FROM player_wallets WHERE user_id = ? FOR UPDATE",
            Integer.class, playerId);
    }

    private record Product(String id, String category, int price, boolean enabled) {}

    private Product product(String itemId) {
        List<Product> rows = jdbc.query("""
            SELECT id, category, price, enabled FROM store_items WHERE id = ?
            """, (rs, row) -> new Product(rs.getString("id"),
                rs.getString("category"), rs.getInt("price"), rs.getBoolean("enabled")),
            itemId);
        if (rows.isEmpty()) {
            throw new StoreException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Item não encontrado.");
        }
        return rows.getFirst();
    }

    private void requireEnabled(Product item) {
        if (!item.enabled()) {
            throw new StoreException(HttpStatus.CONFLICT, "ITEM_UNAVAILABLE",
                "Esse item ainda não está disponível.");
        }
    }

    private boolean owns(long playerId, String itemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM player_items WHERE user_id = ? AND item_id = ?)
            """, Boolean.class, playerId, itemId));
    }

    private StoreDTO snapshot(long playerId) {
        Integer credits = jdbc.queryForObject(
            "SELECT credits FROM player_wallets WHERE user_id = ?", Integer.class, playerId);
        List<StoreDTO.Item> items = jdbc.query("""
            SELECT i.id, i.category, i.name, i.description, i.price, i.enabled,
                   (pi.item_id IS NOT NULL) AS owned,
                   (pe.item_id IS NOT NULL) AS equipped
            FROM store_items i
            LEFT JOIN player_items pi ON pi.item_id = i.id AND pi.user_id = ?
            LEFT JOIN player_equipment pe ON pe.item_id = i.id
                 AND pe.user_id = ? AND pe.category = i.category
            ORDER BY i.display_order, i.id
            """, (rs, row) -> new StoreDTO.Item(
                rs.getString("id"), rs.getString("category"), rs.getString("name"),
                rs.getString("description"), rs.getInt("price"), rs.getBoolean("enabled"),
                rs.getBoolean("owned"), rs.getBoolean("equipped")), playerId, playerId);
        return new StoreDTO(credits, items);
    }

    @Transactional
    public StoreDTO catalog(long playerId) {
        provision(playerId);
        return snapshot(playerId);
    }

    @Transactional
    public StoreDTO purchase(long playerId, String itemId) {
        provision(playerId);
        // Serializes simultaneous purchases across HTTP requests/instances.
        int credits = lockedCredits(playerId);
        Product item = product(itemId);
        requireEnabled(item);
        if (owns(playerId, itemId)) {
            throw new StoreException(HttpStatus.CONFLICT, "ALREADY_OWNED",
                "Este item já está no seu inventário.");
        }
        if (credits < item.price()) {
            throw new StoreException(HttpStatus.CONFLICT, "INSUFFICIENT_CREDITS",
                "Créditos de teste insuficientes.");
        }
        jdbc.update("UPDATE player_wallets SET credits = credits - ? WHERE user_id = ?",
            item.price(), playerId);
        jdbc.update("INSERT INTO player_items (user_id, item_id) VALUES (?, ?)",
            playerId, itemId);
        return snapshot(playerId);
    }

    @Transactional
    public StoreDTO equip(long playerId, String itemId) {
        provision(playerId);
        // Wallet lock also serializes changes to a player's collection.
        lockedCredits(playerId);
        Product item = product(itemId);
        requireEnabled(item);
        if (!owns(playerId, itemId)) {
            throw new StoreException(HttpStatus.CONFLICT, "NOT_OWNED",
                "Você precisa adquirir o item antes de equipá-lo.");
        }
        jdbc.update("""
            INSERT INTO player_equipment (user_id, category, item_id)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id, category) DO UPDATE SET item_id = EXCLUDED.item_id
            """, playerId, item.category(), itemId);
        return snapshot(playerId);
    }
}
