package com.FGInteractive.BatalhaNaval.match.model;

import com.FGInteractive.BatalhaNaval.match.dto.PreparationResponse;
import com.FGInteractive.BatalhaNaval.match.mode.GameModeDefinition;
import java.util.*;

/** Mutable per-lobby state; guarded by MatchmakingService's shared mutex. */
public final class PreparationRound {
    private final String lobbyCode;
    private final UUID id = UUID.randomUUID();
    private final long hostId;
    private final long guestId;
    private final GameModeDefinition mode; // Immutable mode snapshot for this round.
    private final Map<Long, Board> fleets = new HashMap<>();
    private final Set<Long> confirmed = new HashSet<>();
    private long revision = 1;

    public PreparationRound(String lobbyCode, long hostId, long guestId,
                            GameModeDefinition mode) {
        if (hostId == guestId) throw new IllegalArgumentException("Players must be distinct");
        this.lobbyCode = lobbyCode;
        this.hostId = hostId;
        this.guestId = guestId;
        this.mode = Objects.requireNonNull(mode);
    }

    public GameModeDefinition mode() { return mode; }

    public void place(long player, Board board) {
        requireParticipant(player);
        if (confirmed.contains(player)) throw new IllegalStateException("Fleet already confirmed");
        fleets.put(player, Objects.requireNonNull(board));
        revision++;
    }

    public void confirm(long player) {
        requireParticipant(player);
        if (!fleets.containsKey(player)) throw new IllegalStateException("Place your fleet first");
        if (confirmed.add(player)) revision++;
    }

    public PreparationResponse view(long player) {
        requireParticipant(player);
        long rival = player == hostId ? guestId : hostId;
        Board own = fleets.get(player);
        return new PreparationResponse(lobbyCode, id, mode.geometry().rows(), revision,
            own != null, confirmed.contains(player), fleets.containsKey(rival),
            confirmed.contains(rival), confirmed.size() == 2,
            own == null ? List.of() : own.ships(), mode.summary());
    }

    private void requireParticipant(long player) {
        if (player != hostId && player != guestId)
            throw new IllegalArgumentException("Not a participant");
    }
}
