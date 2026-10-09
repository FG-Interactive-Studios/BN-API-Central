package com.FGInteractive.BatalhaNaval.match.service;

import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest;
import com.FGInteractive.BatalhaNaval.match.dto.PreparationResponse;
import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.PreparationRound;
import com.FGInteractive.BatalhaNaval.matchmaking.model.LobbyRoom;
import com.FGInteractive.BatalhaNaval.matchmaking.service.MatchmakingService;
import com.FGInteractive.BatalhaNaval.shared.websocket.RealtimeWebSocketHandler;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MatchService {
    private final MatchmakingService matchmaking;
    private final MatchPreparationStore preparations;
    private final RealtimeWebSocketHandler realtime;

    public MatchService(MatchmakingService matchmaking, MatchPreparationStore preparations,
                        RealtimeWebSocketHandler realtime) {
        this.matchmaking = matchmaking;
        this.preparations = preparations;
        this.realtime = realtime;
    }

    public PreparationResponse mine(long player) {
        return matchmaking.withPreparingRoom(player, room ->
            preparations.get(room.code()).view(player));
    }

    public PreparationResponse place(long player, PlaceFleetRequest request) {
        if (request == null) throw problem(HttpStatus.BAD_REQUEST, "Fleet is required");
        Board board;
        try {
            board = Board.from(request.ships());
        } catch (IllegalArgumentException ex) {
            throw problem(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        return modify(player, round -> round.place(player, board));
    }

    public PreparationResponse random(long player) {
        Board board = Board.random();
        return modify(player, round -> round.place(player, board));
    }

    public PreparationResponse confirm(long player) {
        return modify(player, round -> round.confirm(player));
    }

    private PreparationResponse modify(long player, Consumer<PreparationRound> action) {
        Changes changes = matchmaking.withPreparingRoom(player, room -> {
            PreparationRound round = preparations.get(room.code());
            try {
                action.accept(round);
            } catch (IllegalStateException ex) {
                throw problem(HttpStatus.CONFLICT, ex.getMessage());
            }
            return new Changes(player, room.hostId(), room.guestId(),
                round.view(player), round.view(room.hostId()), round.view(room.guestId()));
        });
        // Separate, sanitized payload for each participant; never publish rival ships.
        realtime.sendToPlayer(changes.hostId(), "PREPARATION_UPDATED", changes.host());
        realtime.sendToPlayer(changes.guestId(), "PREPARATION_UPDATED", changes.guest());
        return changes.self();
    }

    private static ResponseStatusException problem(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private record Changes(long player, long hostId, long guestId,
                           PreparationResponse self, PreparationResponse host,
                           PreparationResponse guest) {}
}
