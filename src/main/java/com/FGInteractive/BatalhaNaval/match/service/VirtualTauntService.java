package com.FGInteractive.BatalhaNaval.match.service;

import com.FGInteractive.BatalhaNaval.matchmaking.model.LobbyRoom;
import com.FGInteractive.BatalhaNaval.matchmaking.service.MatchmakingService;
import com.FGInteractive.BatalhaNaval.shared.websocket.RealtimeWebSocketHandler;
import com.FGInteractive.BatalhaNaval.store.service.StoreService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cosmetic only. Players can transmit exclusively server-defined phrases from
 * their OWNED and EQUIPPED virtual pack, not arbitrary chat text.
 */
@Service
public class VirtualTauntService {
    private static final Map<String,List<String>> PACKS = Map.of(
        "taunt_default", List.of(
            "Foi sem querer! 😇",
            "Isso foi calculado. Confia! 🧠",
            "Parabéns, você acertou água! 🌊",
            "Minha bússola pediu demissão! 🧭"),
        "taunt_banter", List.of(
            "É pavê ou pra afundar? 🍮",
            "Meu barco é tão bom que dá até nó! 🪢",
            "Navio sem mar? Tá mal! 🛟",
            "Não sou capitão, sou capi-tão azarado! 🤡"),
        "taunt_panic", List.of(
            "EU JURO QUE TINHA UM NAVIO AQUI! 😭",
            "CHAMA A SEGURADORA! 💥",
            "A TRIPULAÇÃO ESTÁ EM MODO PÂNICO! 🚨",
            "A CULPA FOI DO VENTO! 🌪️")
    );
    private static final long COOLDOWN_MILLIS = 5000;
    private final MatchmakingService matchmaking;
    private final StoreService store;
    private final RealtimeWebSocketHandler realtime;
    private final ConcurrentHashMap<String,Long> lastSend = new ConcurrentHashMap<>();

    public VirtualTauntService(MatchmakingService matchmaking, StoreService store,
                               RealtimeWebSocketHandler realtime) {
        this.matchmaking=matchmaking;this.store=store;this.realtime=realtime;
    }

    public record TauntMessage(String id, String packId, String message,
                               long senderId, String roomCode, Instant sentAt) {}

    public TauntMessage send(long userId, Integer index) {
        if(index==null || index<0 || index>=4)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid provocation index");
        // Provision free pack for players who entered battle without opening the store.
        var catalog=store.catalog(userId);
        String equipped=catalog.items().stream().filter(item ->
            item.category().equals("TAUNT") && item.owned() && item.equipped())
            .map(item->item.id()).findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT,"Equip a provocation pack first"));
        List<String> phrases=PACKS.get(equipped);
        if(phrases==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Unknown equipped pack");
        TauntMessage event=matchmaking.withBattleRoom(userId, room -> {
            if(room.phase()!=LobbyRoom.Phase.PLAYING)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Provocations only work during battle");
            String key=room.code()+":"+userId;
            long now=System.currentTimeMillis();
            long previous=lastSend.getOrDefault(key,0L);
            if(now-previous<COOLDOWN_MILLIS)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Wait five seconds between provocations");
            lastSend.put(key,now);
            return new TauntMessage(room.code()+":"+userId+":"+now, equipped,
                phrases.get(index),userId,room.code(),Instant.now());
        });
        // Send only after server membership, ownership and rate-limit checks.
        var opponents=matchmaking.withBattleRoom(userId, room ->
            List.of(room.hostId(),room.guestId()));
        for(long recipient:opponents)realtime.sendToPlayer(recipient,"TAUNT_SENT",event);
        return event;
    }
}
