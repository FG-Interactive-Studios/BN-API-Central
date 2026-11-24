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
    @org.springframework.beans.factory.annotation.Value("${store.demo-premium-enabled:true}")
    private boolean demoPremiumEnabled;

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
            SELECT ?, id FROM store_items WHERE enabled = TRUE AND price = 0 AND premium_only = FALSE AND category <> 'BUNDLE'
            ON CONFLICT DO NOTHING
            """, playerId);
        jdbc.update("""
            INSERT INTO player_equipment (user_id, category, item_id)
            SELECT ?, category, id FROM store_items WHERE enabled = TRUE AND price = 0 AND premium_only = FALSE AND category <> 'BUNDLE'
            ON CONFLICT (user_id, category) DO NOTHING
            """, playerId);
    }

    private int lockedCredits(long playerId) {
        return jdbc.queryForObject(
            "SELECT credits FROM player_wallets WHERE user_id = ? FOR UPDATE",
            Integer.class, playerId);
    }

    private record Product(String id, String category, int price, boolean enabled, boolean premiumOnly) {}

    private Product product(String itemId) {
        List<Product> rows = jdbc.query("""
            SELECT id, category, price, enabled, premium_only FROM store_items WHERE id = ?
            """, (rs, row) -> new Product(rs.getString("id"),
                rs.getString("category"), rs.getInt("price"), rs.getBoolean("enabled"), rs.getBoolean("premium_only")),
            itemId);
        if (rows.isEmpty()) {
            throw new StoreException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Item não encontrado.");
        }
        return rows.getFirst();
    }

    private void requireEnabled(Product item) {
        if (!item.enabled() || !List.of("SKIN","SOUND","TAUNT","CAPTAIN","BUNDLE","ATTACK","CELEBRATION","FUNERAL","TITLE","COMMENTATOR","FRAME","BOARD_THEME").contains(item.category())) {
            throw new StoreException(HttpStatus.CONFLICT, "ITEM_UNAVAILABLE",
                "Esse item ainda não está disponível.");
        }
    }

    private boolean owns(long playerId, String itemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM player_items WHERE user_id = ? AND item_id = ?)
            """, Boolean.class, playerId, itemId));
    }

    /** Safe public presentation only: no wallet, ownership history or hidden fleet state. */
    public java.util.Map<String,String> equippedPresentation(long playerId) {
        var result=new java.util.HashMap<String,String>();
        jdbc.query("""
            SELECT i.category,i.id FROM player_equipment e
            JOIN store_items i ON i.id=e.item_id AND i.category=e.category
            WHERE e.user_id=? AND i.enabled=TRUE
              AND i.category IN ('CAPTAIN','SKIN','ATTACK','CELEBRATION','FUNERAL','TITLE','COMMENTATOR','FRAME','BOARD_THEME')
            """, rs -> { result.put(rs.getString("category"),rs.getString("id")); },playerId);
        return java.util.Map.copyOf(result);
    }

    private List<String> bundleComponents(String id) {
        return jdbc.queryForList("SELECT item_id FROM store_bundle_components WHERE bundle_id=? ORDER BY item_id",
            String.class,id);
    }

    private List<String> purchasableComponents(Product item) {
        if (!item.category().equals("BUNDLE")) return List.of();
        var components=bundleComponents(item.id());
        if (components.isEmpty()) throw new StoreException(HttpStatus.CONFLICT,"ITEM_UNAVAILABLE","Coleção sem itens disponíveis.");
        for(String id:components) {
            Product component=product(id);
            if(component.category().equals("BUNDLE")) throw new StoreException(HttpStatus.CONFLICT,"ITEM_UNAVAILABLE","Coleção inválida.");
            requireEnabled(component);
        }
        return components;
    }

    private static int premiumPrice(int credits) {
        return credits / 3 + (credits % 3 == 0 ? 0 : 1);
    }

    private void requireDemoPremium() {
        if (!demoPremiumEnabled) throw new StoreException(HttpStatus.CONFLICT,
            "DEMO_PREMIUM_DISABLED", "A economia premium de demonstração está desativada.");
    }

    @Transactional
    public StoreDTO demoTopup(long playerId, String packId, java.util.UUID requestId) {
        requireDemoPremium();
        int amount = switch (packId == null ? "" : packId) {
            case "demo_100" -> 100;
            case "demo_300" -> 300;
            case "demo_700" -> 700;
            default -> throw new StoreException(HttpStatus.BAD_REQUEST, "INVALID_DEMO_PACK",
                "Pacote de demonstração inválido.");
        };
        if (requestId == null) throw new StoreException(HttpStatus.BAD_REQUEST,
            "REQUEST_ID_REQUIRED", "Identificador da operação obrigatório.");
        provision(playerId);
        lockedCredits(playerId);
        var prior = jdbc.queryForList("""
            SELECT pack_id FROM demo_premium_topups WHERE user_id=? AND request_id=?
            """, String.class, playerId, requestId);
        if (!prior.isEmpty()) {
            if (!prior.getFirst().equals(packId)) throw new StoreException(HttpStatus.CONFLICT,
                "REQUEST_ID_REUSED", "Identificador já utilizado para outro pacote.");
            return snapshot(playerId);
        }
        int balance = jdbc.queryForObject("SELECT premium_credits FROM player_wallets WHERE user_id=?",
            Integer.class, playerId);
        if (balance > 10000 - amount) throw new StoreException(HttpStatus.CONFLICT,
            "DEMO_BALANCE_LIMIT", "Limite de créditos premium de demonstração atingido.");
        jdbc.update("""
            INSERT INTO demo_premium_topups(user_id,request_id,pack_id,amount) VALUES (?,?,?,?)
            """,playerId,requestId,packId,amount);
        jdbc.update("UPDATE player_wallets SET premium_credits=premium_credits+? WHERE user_id=?",
            amount,playerId);
        return snapshot(playerId);
    }

    private StoreDTO snapshot(long playerId) {
        var wallet = jdbc.queryForMap(
            "SELECT credits, premium_credits FROM player_wallets WHERE user_id = ?", playerId);
        int credits = ((Number) wallet.get("credits")).intValue();
        int premiumCredits = ((Number) wallet.get("premium_credits")).intValue();
        List<StoreDTO.Item> items = jdbc.query("""
            SELECT i.id, i.category, i.name, i.description, i.price, i.enabled, i.premium_only,
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
                rs.getBoolean("owned"), rs.getBoolean("equipped"), rs.getBoolean("premium_only")?rs.getInt("price"):premiumPrice(rs.getInt("price")), rs.getString("category").equals("BUNDLE")?bundleComponents(rs.getString("id")):List.of(),rs.getBoolean("premium_only")), playerId, playerId);
        return new StoreDTO(credits, premiumCredits, items, demoPremiumEnabled);
    }

    @Transactional
    public StoreDTO catalog(long playerId) {
        provision(playerId);
        return snapshot(playerId);
    }

    @Transactional
    public StoreDTO purchase(long playerId, String itemId) {
        return purchase(playerId, itemId, false);
    }

    @Transactional
    public StoreDTO purchase(long playerId, String itemId, boolean premium) {
        if (premium) requireDemoPremium();
        provision(playerId);
        // Serializes simultaneous purchases across HTTP requests/instances.
        int credits = lockedCredits(playerId);
        Product item = product(itemId);
        requireEnabled(item);
        if (owns(playerId, itemId)) {
            throw new StoreException(HttpStatus.CONFLICT, "ALREADY_OWNED",
                "Este item já está no seu inventário.");
        }
        if (!premium && item.premiumOnly()) throw new StoreException(HttpStatus.CONFLICT,"PREMIUM_REQUIRED","Use moedas navais para adquirir este cosmético.");
        List<String> components=purchasableComponents(item);
        if (!components.isEmpty() && components.stream().allMatch(id->owns(playerId,id)))
            throw new StoreException(HttpStatus.CONFLICT,"ALREADY_OWNED","Você já possui todos os itens desta coleção.");
        int price = premium && !item.premiumOnly() ? premiumPrice(item.price()) : item.price();
        int balance = premium ? jdbc.queryForObject(
            "SELECT premium_credits FROM player_wallets WHERE user_id=?", Integer.class, playerId) : credits;
        if (balance < price) {
            throw new StoreException(HttpStatus.CONFLICT, "INSUFFICIENT_CREDITS",
                "Créditos de teste insuficientes.");
        }
        jdbc.update(premium
            ? "UPDATE player_wallets SET premium_credits = premium_credits - ? WHERE user_id = ?"
            : "UPDATE player_wallets SET credits = credits - ? WHERE user_id = ?", price, playerId);
        jdbc.update("INSERT INTO player_items (user_id, item_id) VALUES (?, ?)",
            playerId, itemId);
        for(String component:components) jdbc.update(
            "INSERT INTO player_items(user_id,item_id) VALUES (?,?) ON CONFLICT DO NOTHING",playerId,component);
        return snapshot(playerId);
    }

    /** Fake coins only: one server-derived reward per finished match and user. */
    public record RewardReceipt(int earned, StoreDTO wallet) {}

    @Transactional
    public RewardReceipt claimVirtualMatchReward(long playerId, java.util.UUID matchId,
                                                  Long winnerId) {
        if(matchId==null) throw new StoreException(HttpStatus.CONFLICT,
            "MATCH_NOT_FINISHED","A partida ainda não terminou.");
        provision(playerId);
        lockedCredits(playerId);
        int amount=winnerId==null?30:(winnerId.longValue()==playerId?60:25);
        int granted=jdbc.update("""
            INSERT INTO virtual_match_rewards (user_id,match_id,credits)
            VALUES (?,?,?) ON CONFLICT (user_id,match_id) DO NOTHING
            """,playerId,matchId,amount);
        if(granted>0)jdbc.update(
            "UPDATE player_wallets SET credits=credits+? WHERE user_id=?",amount,playerId);
        return new RewardReceipt(granted>0?amount:0,snapshot(playerId));
    }

    @Transactional
    public StoreDTO equip(long playerId, String itemId) {
        provision(playerId);
        // Wallet lock also serializes changes to a player's collection.
        lockedCredits(playerId);
        Product item = product(itemId);
        requireEnabled(item);
        if (item.category().equals("BUNDLE")) throw new StoreException(HttpStatus.CONFLICT,"ITEM_UNAVAILABLE","Equipe os itens da coleção individualmente.");
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
