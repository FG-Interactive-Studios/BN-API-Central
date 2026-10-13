package com.FGInteractive.BatalhaNaval.match.service;

import com.FGInteractive.BatalhaNaval.match.dto.BattleResponse;
import com.FGInteractive.BatalhaNaval.match.dto.FireRequest;
import com.FGInteractive.BatalhaNaval.match.model.BattleRound;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import com.FGInteractive.BatalhaNaval.matchmaking.model.LobbyRoom;
import com.FGInteractive.BatalhaNaval.matchmaking.service.MatchmakingService;
import com.FGInteractive.BatalhaNaval.shared.websocket.RealtimeWebSocketHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Commands run under the matchmaking mutex, never trusting a client-supplied player ID. */
@Service
public class BattleService {
    private final MatchmakingService matchmaking;
    private final MatchPreparationStore store;
    private final RealtimeWebSocketHandler realtime;

    public BattleService(MatchmakingService matchmaking, MatchPreparationStore store,
                         RealtimeWebSocketHandler realtime) {
        this.matchmaking = matchmaking;
        this.store = store;
        this.realtime = realtime;
    }

    public BattleResponse mine(long userId) {
        return matchmaking.withBattleRoom(userId,
            room -> store.battle(room.code()).view(userId));
    }

    public BattleResponse fire(long userId, FireRequest request) {
        if (request == null || request.row() == null || request.col() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shot coordinates are required");
        }
        BattleChanges change = matchmaking.withBattleRoom(userId, room -> {
            BattleRound battle = store.battle(room.code());
            try {
                battle.fire(userId, new Cell(request.row(), request.col()));
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
            } catch (IllegalStateException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
            }
            if (battle.finished()) room.finishBattle();
            return new BattleChanges(room.hostId(), room.guestId(),
                battle.view(userId), battle.view(room.hostId()), battle.view(room.guestId()),
                battle.finished());
        });
        // Private per-player snapshots. Rival's unhit cells are never included.
        realtime.sendToPlayer(change.hostId(), "BATTLE_UPDATED", change.host());
        realtime.sendToPlayer(change.guestId(), "BATTLE_UPDATED", change.guest());
        if (change.finished()) matchmaking.publishRoomFor(userId);
        return change.self();
    }

    /** Authenticated intent only: loser is derived from the JWT subject. */
    public BattleResponse forfeit(long userId) {
        BattleChanges change = matchmaking.withBattleRoom(userId, room -> {
            BattleRound battle = store.battle(room.code());
            boolean changed;
            try {
                changed = battle.forfeit(userId);
            } catch (IllegalStateException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
            }
            if (changed) room.finishBattle();
            return new BattleChanges(room.hostId(), room.guestId(),
                battle.view(userId), battle.view(room.hostId()),
                battle.view(room.guestId()), changed);
        });
        if (change.finished()) {
            realtime.sendToPlayer(change.hostId(), "BATTLE_UPDATED", change.host());
            realtime.sendToPlayer(change.guestId(), "BATTLE_UPDATED", change.guest());
            matchmaking.publishRoomFor(userId);
        }
        return change.self();
    }

    private record BattleChanges(long hostId, long guestId,
                                 BattleResponse self, BattleResponse host,
                                 BattleResponse guest, boolean finished) {}
}
