package com.FGInteractive.BatalhaNaval.match.model;

import com.FGInteractive.BatalhaNaval.match.dto.PreparationResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/**
 * Mutable per-lobby game preparation, guarded by MatchmakingService's mutex.
 * Never serialize this class directly: only per-player views may leave the service.
 */
public final class PreparationRound {
    private final String lobbyCode;
    private final UUID id = UUID.randomUUID();
    private final long hostId;
    private final long guestId;
    private final Map<Long, Board> fleets = new HashMap<>();
    private final Set<Long> confirmed = new HashSet<>();
    private long revision = 1;

    public PreparationRound(String lobbyCode, long hostId, long guestId) {
        if (hostId == guestId) throw new IllegalArgumentException("Players must be distinct");
        this.lobbyCode = lobbyCode;
        this.hostId = hostId;
        this.guestId = guestId;
    }

    public void place(long player, Board board) {
        requireParticipant(player);
        if (confirmed.contains(player)) throw new IllegalStateException("Fleet already confirmed");
        fleets.put(player, board);
        revision++;
    }

    public void confirm(long player) {
        requireParticipant(player);
        if (!fleets.containsKey(player)) throw new IllegalStateException("Place your fleet first");
        if (confirmed.add(player)) revision++; // Confirming twice is idempotent.
    }

    public PreparationResponse view(long player) {
        requireParticipant(player);
        long rival = player == hostId ? guestId : hostId;
        Board own = fleets.get(player);
        return new PreparationResponse(lobbyCode, id, Board.SIZE, revision,
            own != null, confirmed.contains(player), fleets.containsKey(rival),
            confirmed.contains(rival), confirmed.size() == 2,
            own == null ? java.util.List.of() : own.ships());
    }

    private void requireParticipant(long player) {
        if (player != hostId && player != guestId) {
            throw new IllegalArgumentException("Not a participant");
        }
    }
}
