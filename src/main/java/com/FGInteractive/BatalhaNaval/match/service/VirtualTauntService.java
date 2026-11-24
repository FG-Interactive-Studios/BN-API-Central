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
            "Minha bússola pediu demissão! 🧭",
            "Eu ouvi um ploc. Isso conta como acerto? 🫧",
            "Meu mapa veio de um guardanapo. 🗺️",
            "É uma manobra de distração, tá? 🎭",
            "Vou fingir que foi de propósito. 😎",
            "Alguém viu onde estacionei minha frota? 🚗",
            "Nota oficial: tudo sob controle (mentira). 📋"
        ),
        "taunt_banter", List.of(
            "É pavê ou pra afundar? 🍮",
            "Meu barco é tão bom que dá até nó! 🪢",
            "Navio sem mar? Tá mal! 🛟",
            "Não sou capitão, sou capi-tão azarado! 🤡",
            "É afundar ou é pra boiar? 🛟",
            "Esse ataque foi mar-avilhoso! 🌊",
            "Você viu meu trocadilho boiando? 🤿",
            "Se eu errar de novo, é maré de azar! 🍀",
            "Essa vitória tá vindo a remo! 🚣",
            "Meu radar tá de férias no litoral. 🏖️"
        ),
        "taunt_panic", List.of(
            "EU JURO QUE TINHA UM NAVIO AQUI! 😭",
            "CHAMA A SEGURADORA! 💥",
            "A TRIPULAÇÃO ESTÁ EM MODO PÂNICO! 🚨",
            "A CULPA FOI DO VENTO! 🌪️",
            "ALGUÉM DESLIGA O MAR! 🚨",
            "SE ACHAR MEU CANHÃO, ME AVISA! 📢",
            "COMO ASSIM O NAVIO NÃO TEM FREIO?! 🛑",
            "PRECISO DE UM ADULTO RESPONSÁVEL! 😵",
            "CAPITÃO, O MANUAL TÁ MOLHADO! 📖",
            "ESTOU NEGOCIANDO COM OS PEIXES! 🐟"
        ),
        "taunt_support", List.of(
            "Seu naufrágio é muito importante para nós. ☎️",
            "Protocolo aberto: água onde não devia. 🧾",
            "Nosso prazo é de três marés úteis. ⌛",
            "Para reclamar, disque 0800-AFUNDE. 📞",
            "Sua embarcação foi encaminhada ao suporte. 🛠️",
            "Aperte 1 para pânico, 2 para mais pânico. ☎️",
            "O seguro cobre danos emocionais? 📄",
            "Aguarde na linha. O casco já não aguarda. 📠",
            "Seu chamado foi encerrado por afogamento. 🗂️",
            "Nos avalie de 1 a naufrágio. ⭐"
        ),
        "taunt_meme", List.of(
            "Achei que fosse batalha naval, não aula de natação! 🏊",
            "Confia no processo. O processo afundou. 📉",
            "Esse tiro foi patrocinado pelo acaso. 🎲",
            "Alguém atualiza o currículo do meu navio? 💼",
            "Minha estratégia veio de um sonho estranho. 🛌",
            "Tá tudo bem. Só não olha pro casco. 🙈",
            "Esse oceano tá com hitbox duvidosa! 🎯",
            "Foi lag do universo, eu juro! 📶",
            "Acabou o orçamento para mira. 💸",
            "Meu capitão está em manutenção. 🔧"
        )
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

    private record Delivery(TauntMessage event, List<Long> recipients) {}

    public TauntMessage send(long userId, Integer index) {
        if(index==null || index<0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid provocation index");
        // Provision free pack for players who entered battle without opening the store.
        var catalog=store.catalog(userId);
        String equipped=catalog.items().stream().filter(item ->
            item.category().equals("TAUNT") && item.owned() && item.equipped())
            .map(item->item.id()).findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT,"Equip a provocation pack first"));
        List<String> phrases=PACKS.get(equipped);
        if(phrases==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Unknown equipped pack");
        if(index>=phrases.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid provocation index");
        Delivery delivery=matchmaking.withBattleRoom(userId, room -> {
            if(room.phase()!=LobbyRoom.Phase.PLAYING)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Provocations only work during battle");
            String key=room.code()+":"+userId;
            long now=System.currentTimeMillis();
            long previous=lastSend.getOrDefault(key,0L);
            if(now-previous<COOLDOWN_MILLIS)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Wait five seconds between provocations");
            lastSend.put(key,now);
            TauntMessage event=new TauntMessage(room.code()+":"+userId+":"+now, equipped,
                phrases.get(index),userId,room.code(),Instant.now());
            return new Delivery(event,List.of(room.hostId(),room.guestId()));
        });
        // Send only after server membership, ownership and rate-limit checks.
        for(long recipient:delivery.recipients())
            realtime.sendToPlayer(recipient,"TAUNT_SENT",delivery.event());
        return delivery.event();
    }
}
