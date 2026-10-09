package com.FGInteractive.BatalhaNaval.match.model;

import com.FGInteractive.BatalhaNaval.match.dto.BattleResponse;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import java.util.*;

/** In-memory, server-authoritative match. All access under the lobby mutex. */
public final class BattleRound {
    private final UUID id = UUID.randomUUID();
    private final String lobbyCode;
    private final GameModeDefinition mode;
    private final long hostId;
    private final long guestId;
    private final Map<Long, Board> boards;
    private final Map<Long, LinkedHashMap<Cell, ShotRecord>> shots = new HashMap<>();
    private long turnPlayerId;
    private Long winnerId;
    private long revision = 1;

    public BattleRound(String lobbyCode, GameModeDefinition mode, long hostId, long guestId,
                       Board hostBoard, Board guestBoard) {
        if (hostId == guestId) throw new IllegalArgumentException("Two unique players are required");
        this.lobbyCode = Objects.requireNonNull(lobbyCode);
        this.mode = Objects.requireNonNull(mode);
        this.hostId = hostId;
        this.guestId = guestId;
        boards = Map.of(hostId, Objects.requireNonNull(hostBoard),
                        guestId, Objects.requireNonNull(guestBoard));
        shots.put(hostId, new LinkedHashMap<>());
        shots.put(guestId, new LinkedHashMap<>());
        turnPlayerId = hostId; // Explicit deterministic opening rule for V1.
    }

    public boolean finished() { return winnerId != null; }

    public void fire(long attacker, Cell target) {
        requireParticipant(attacker);
        if (finished()) throw new IllegalStateException("Match already finished");
        if (turnPlayerId != attacker) throw new IllegalStateException("Not your turn");
        if (!mode.geometry().contains(target))
            throw new IllegalArgumentException("Target is outside the playable board");
        var ownShots = shots.get(attacker);
        if (ownShots.containsKey(target)) throw new IllegalStateException("Cell already targeted");

        long defender = attacker == hostId ? guestId : hostId;
        Board defendingBoard = boards.get(defender);
        Set<Cell> firedCells = new HashSet<>(ownShots.keySet());
        firedCells.add(target);

        AttackRule attack = (AttackRule) mode.rules().get(RuleSlot.ATTACK);
        VictoryRule victory = (VictoryRule) mode.rules().get(RuleSlot.VICTORY);
        TurnRule turns = (TurnRule) mode.rules().get(RuleSlot.TURN);
        ShotImpact impact = attack.resolve(mode, defendingBoard, Set.copyOf(firedCells), target);
        boolean defeated = victory.defeated(defendingBoard, Set.copyOf(firedCells));
        long next = defeated ? attacker : turns.nextPlayer(attacker, defender, impact);
        if (!defeated && next != hostId && next != guestId)
            throw new IllegalStateException("Turn rule selected a non-participant");

        ownShots.put(target, new ShotRecord(target.row(), target.col(),
            impact.hit(), impact.sunkShip()));
        if (defeated) winnerId = attacker;
        turnPlayerId = next;
        revision++;
    }

    public BattleResponse view(long player) {
        requireParticipant(player);
        long rival = player == hostId ? guestId : hostId;
        return new BattleResponse(id, lobbyCode, mode.summary(),
            finished() ? "FINISHED" : "PLAYING", revision,
            finished() ? null : turnPlayerId, winnerId,
            boards.get(player).ships(), List.copyOf(shots.get(player).values()),
            List.copyOf(shots.get(rival).values()));
    }

    private void requireParticipant(long player) {
        if (player != hostId && player != guestId)
            throw new IllegalArgumentException("Not a participant");
    }
}
