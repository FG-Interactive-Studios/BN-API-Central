package com.FGInteractive.BatalhaNaval.match.model;

import com.FGInteractive.BatalhaNaval.match.dto.BattleResponse;
import com.FGInteractive.BatalhaNaval.match.dto.AbilityStatus;
import com.FGInteractive.BatalhaNaval.match.dto.RadarReport;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import java.util.*;
import java.time.Duration;
import java.time.Instant;

/** In-memory, server-authoritative match. All access under the lobby mutex. */
public final class BattleRound {
    private final UUID id = UUID.randomUUID();
    private final String lobbyCode;
    private final GameModeDefinition mode;
    private final long hostId;
    private final long guestId;
    private final Map<Long, Board> boards;
    private final Map<Long, LinkedHashMap<Cell, ShotRecord>> shots = new HashMap<>();
    private final Map<Long, Set<String>> usedAbilities = new HashMap<>();
    private final Map<Long, List<RadarReport>> radarReports = new HashMap<>();
    private long turnPlayerId;
    private Long winnerId;
    private String finishReason;
    /** Deadline exists only while a player has no authenticated WebSocket. */
    private final Map<Long, Instant> reconnectDeadlines = new HashMap<>();
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
        usedAbilities.put(hostId, new HashSet<>());
        usedAbilities.put(guestId, new HashSet<>());
        radarReports.put(hostId, new ArrayList<>());
        radarReports.put(guestId, new ArrayList<>());
        turnPlayerId = hostId; // Explicit deterministic opening rule for V1.
    }

    public boolean finished() { return finishReason != null; }

    /**
     * Called under the shared match/lobby lock, including at the start of
     * PLAYING. Multiple tabs count as connected while at least one remains.
     */
    public boolean setPlayerConnected(long player, boolean connected, Instant now,
                                      Duration grace) {
        requireParticipant(player);
        if (finished()) return false;
        if (connected) {
            if (reconnectDeadlines.remove(player) == null) return false;
        } else {
            if (reconnectDeadlines.containsKey(player)) return false;
            reconnectDeadlines.put(player, now.plus(grace));
        }
        revision++;
        return true;
    }

    /** User-initiated surrender. Kept separate from a rules-based game win. */
    public boolean forfeit(long player) {
        requireParticipant(player);
        if (finished()) {
            // Safe retry from the same forfeiting player, never reassign winner.
            if ("ABANDONED".equals(finishReason) && !Objects.equals(winnerId, player))
                return false;
            throw new IllegalStateException("Match already finished");
        }
        finish(player == hostId ? guestId : hostId, "ABANDONED");
        return true;
    }

    /**
     * A disconnect alone cannot end a battle: only an expired deadline can.
     * If both players expire, cancel without manufacturing a winner. When
     * one expires but the other is also offline inside its grace window,
     * wait for a reconnect or for that window to expire.
     */
    public boolean resolveDisconnects(Instant now) {
        if (finished()) return false;
        boolean hostExpired = expired(hostId, now);
        boolean guestExpired = expired(guestId, now);
        if (hostExpired && guestExpired) {
            finish(null, "BOTH_DISCONNECTED");
        } else if (hostExpired && !reconnectDeadlines.containsKey(guestId)) {
            finish(guestId, "DISCONNECT_TIMEOUT");
        } else if (guestExpired && !reconnectDeadlines.containsKey(hostId)) {
            finish(hostId, "DISCONNECT_TIMEOUT");
        } else {
            return false;
        }
        return true;
    }

    private boolean expired(long player, Instant now) {
        Instant deadline = reconnectDeadlines.get(player);
        return deadline != null && !deadline.isAfter(now);
    }

    private void finish(Long winner, String reason) {
        winnerId = winner;
        finishReason = reason;
        reconnectDeadlines.clear();
        revision++;
    }

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
        if (defeated) {
            winnerId = attacker;
            finishReason = "ALL_SHIPS_SUNK";
            reconnectDeadlines.clear();
        }
        turnPlayerId = next;
        revision++;
    }


    /**
     * Executes one authenticated action atomically (caller holds lobby mutex).
     * Two shots spend one turn; scan reports are visible only to their owner.
     */
    public void useAbility(long attacker, String abilityId, List<Cell> targets) {
        requireParticipant(attacker);
        if (finished()) throw new IllegalStateException("Match already finished");
        if (turnPlayerId != attacker) throw new IllegalStateException("Not your turn");
        if (!(mode.rules().get(RuleSlot.ABILITY) instanceof AbilityRule ability))
            throw new IllegalStateException("Abilities are disabled in this mode");
        AbilitySpec spec = ability.catalog().stream()
            .filter(entry -> entry.id().equals(abilityId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown ability"));
        if (usedAbilities.get(attacker).contains(spec.id()))
            throw new IllegalStateException("Ability usage limit reached");
        long defender = attacker == hostId ? guestId : hostId;
        AbilityOutcome outcome = ability.resolve(abilityId, targets, mode, boards.get(defender));
        LinkedHashMap<Cell, ShotRecord> ownShots = shots.get(attacker);
        // All preconditions are verified BEFORE any shot, usage counter or report changes.
        if (outcome.shots().stream().anyMatch(ownShots::containsKey))
            throw new IllegalStateException("Cell already targeted");

        AttackRule attack = (AttackRule) mode.rules().get(RuleSlot.ATTACK);
        VictoryRule victory = (VictoryRule) mode.rules().get(RuleSlot.VICTORY);
        TurnRule turns = (TurnRule) mode.rules().get(RuleSlot.TURN);
        ShotImpact lastImpact = new ShotImpact(false, null);
        boolean defeated = false;
        for (Cell cell : outcome.shots()) {
            Set<Cell> firedCells = new HashSet<>(ownShots.keySet());
            firedCells.add(cell);
            lastImpact = attack.resolve(mode, boards.get(defender), Set.copyOf(firedCells), cell);
            ownShots.put(cell, new ShotRecord(cell.row(), cell.col(),
                lastImpact.hit(), lastImpact.sunkShip()));
            defeated = victory.defeated(boards.get(defender), Set.copyOf(firedCells));
            if (defeated) break; // No further shots after victory.
        }
        if (outcome.privateRadarReport() != null)
            radarReports.get(attacker).add(outcome.privateRadarReport());
        usedAbilities.get(attacker).add(spec.id());
        long next = defeated ? attacker : turns.nextPlayer(attacker, defender, lastImpact);
        if (!defeated && next != hostId && next != guestId)
            throw new IllegalStateException("Turn rule selected a non-participant");
        if (defeated) {
            winnerId = attacker;
            finishReason = "ALL_SHIPS_SUNK";
            reconnectDeadlines.clear();
        }
        turnPlayerId = next;
        revision++;
    }

    public BattleResponse view(long player) {
        requireParticipant(player);
        long rival = player == hostId ? guestId : hostId;
        List<AbilityStatus> available = mode.rules().get(RuleSlot.ABILITY) instanceof AbilityRule rule
            ? rule.catalog().stream().map(spec -> new AbilityStatus(
                spec.id(), spec.name(), usedAbilities.get(player).contains(spec.id())
                    ? 0 : spec.maxUses())).toList()
            : List.of();
        return new BattleResponse(id, lobbyCode, mode.summary(),
            finished() ? "FINISHED" : "PLAYING", revision,
            finished() ? null : turnPlayerId, winnerId,
            boards.get(player).ships(), List.copyOf(shots.get(player).values()),
            List.copyOf(shots.get(rival).values()), finishReason,
            Map.copyOf(reconnectDeadlines), available,
            List.copyOf(radarReports.get(player)));
    }

    private void requireParticipant(long player) {
        if (player != hostId && player != guestId)
            throw new IllegalArgumentException("Not a participant");
    }
}
