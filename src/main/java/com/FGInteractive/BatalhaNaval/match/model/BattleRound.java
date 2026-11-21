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
    private final Map<Long, Map<String,Integer>> abilityUseCounts = new HashMap<>();
    private final Map<Long, List<RadarReport>> radarReports = new HashMap<>();
    /** Action counters, not wall time: deterministic even during disconnects. */
    private final Map<Long,Integer> actionsTaken = new HashMap<>();
    private final Map<Long,ShieldState> shields = new HashMap<>();
    private final Map<Long,Integer> lastChanceExpires = new HashMap<>();
    private final Map<Long,Integer> jamUntilAction = new HashMap<>();
    private record ShieldState(String shipType, int expiresAfterEnemyAction) {}
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
        boards = new HashMap<>();
        boards.put(hostId, Objects.requireNonNull(hostBoard));
        boards.put(guestId, Objects.requireNonNull(guestBoard));
        actionsTaken.put(hostId,0);
        actionsTaken.put(guestId,0);
        shots.put(hostId, new LinkedHashMap<>());
        shots.put(guestId, new LinkedHashMap<>());
        abilityUseCounts.put(hostId, new HashMap<>());
        abilityUseCounts.put(guestId, new HashMap<>());
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


    /** Both ordinary and special attacks pass through the same shield-aware resolver. */
    private ShotImpact resolveShot(long attacker, long defender, Cell target) {
        LinkedHashMap<Cell, ShotRecord> fired = shots.get(attacker);
        if (!mode.geometry().contains(target))
            throw new IllegalArgumentException("Target is outside the playable board");
        if (fired.containsKey(target) && !fired.get(target).blocked())
            throw new IllegalStateException("Cell already targeted");
        Board defending=boards.get(defender);

        // Shield only absorbs a hit on the selected ship, not a harmless miss.
        ShieldState shield=shields.get(defender);
        if(shield!=null && actionsTaken.get(attacker)<shield.expiresAfterEnemyAction()
            && defending.occupied().contains(target)) {
            var placement=defending.ships().stream()
                .filter(ship->ship.type().equals(shield.shipType()))
                .findFirst().orElse(null);
            if(placement!=null && mode.placement().footprint(placement,
                mode.fleet().require(placement.type()),mode.geometry()).contains(target)) {
                shields.remove(defender);
                fired.put(target,new ShotRecord(target.row(),target.col(),false,null,true));
                return new ShotImpact(false,null);
            }
        }

        Set<Cell> successful=new HashSet<>();
        fired.forEach((cell,record)->{if(!record.blocked())successful.add(cell);});
        successful.add(target);
        // Last Chance intercepts exactly the shot that would otherwise sink the last ship.
        if(defending.occupied().contains(target)
            && lastChanceExpires.getOrDefault(defender,0)>actionsTaken.get(attacker)
            && successful.containsAll(defending.occupied())) {
            lastChanceExpires.remove(defender);
            fired.put(target,new ShotRecord(target.row(),target.col(),false,null,true));
            return new ShotImpact(false,null);
        }

        ShotImpact impact=((AttackRule)mode.rules().get(RuleSlot.ATTACK))
            .resolve(mode,defending,Set.copyOf(successful),target);
        fired.put(target,new ShotRecord(target.row(),target.col(),
            impact.hit(),impact.sunkShip()));
        return impact;
    }

    private Set<Cell> successfulShots(long player) {
        Set<Cell> cells=new HashSet<>();
        shots.get(player).forEach((cell,record)->{if(!record.blocked())cells.add(cell);});
        return Set.copyOf(cells);
    }

    private void advanceTurn(long attacker,long defender,ShotImpact impact) {
        boolean defeated=((VictoryRule)mode.rules().get(RuleSlot.VICTORY))
            .defeated(boards.get(defender),successfulShots(attacker));
        long next=defeated?attacker:
            ((TurnRule)mode.rules().get(RuleSlot.TURN)).nextPlayer(attacker,defender,impact);
        if(!defeated && next!=hostId && next!=guestId)
            throw new IllegalStateException("Turn rule selected a non-participant");
        if(defeated) {
            winnerId=attacker;
            finishReason="ALL_SHIPS_SUNK";
            reconnectDeadlines.clear();
        }
        turnPlayerId=next;
        actionsTaken.merge(attacker,1,Integer::sum);
        revision++;
    }

    public void fire(long attacker, Cell target) {
        requireParticipant(attacker);
        if(finished()) throw new IllegalStateException("Match already finished");
        if(turnPlayerId!=attacker) throw new IllegalStateException("Not your turn");
        if(!mode.geometry().contains(target))
            throw new IllegalArgumentException("Target is outside the playable board");
        var fired=shots.get(attacker);
        if(fired.containsKey(target)&&!fired.get(target).blocked())
            throw new IllegalStateException("Cell already targeted");
        long defender=attacker==hostId?guestId:hostId;
        ShotImpact impact=resolveShot(attacker,defender,target);
        advanceTurn(attacker,defender,impact);
    }

    public void useAbility(long attacker,String abilityId,List<Cell> targets) {
        requireParticipant(attacker);
        if(finished()) throw new IllegalStateException("Match already finished");
        if(turnPlayerId!=attacker) throw new IllegalStateException("Not your turn");
        if(jamUntilAction.getOrDefault(attacker,0)>actionsTaken.get(attacker))
            throw new IllegalStateException("Abilities jammed until the next turn");
        if(!(mode.rules().get(RuleSlot.ABILITY) instanceof AbilityRule ability))
            throw new IllegalStateException("Abilities are disabled in this mode");
        AbilitySpec spec=ability.catalog().stream()
            .filter(a->a.id().equals(abilityId)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Unknown ability"));
        if(abilityUseCounts.get(attacker).getOrDefault(spec.id(),0)>=spec.maxUses())
            throw new IllegalStateException("Ability usage limit reached");

        long defender=attacker==hostId?guestId:hostId;
        AbilityContext context=new AbilityContext(mode,boards.get(attacker),boards.get(defender),
            successfulShots(attacker),successfulShots(defender));
        AbilityOutcome planned=ability.resolve(abilityId,targets,context);
        // Validate ALL attack cells first; a failed validation must not spend the turn.
        for(AbilityEffect effect:planned.effects()) {
            if(effect instanceof AbilityEffect.Attack attack) {
                if(new HashSet<>(attack.cells()).size()!=attack.cells().size()
                    || attack.cells().isEmpty()
                    || attack.cells().stream().anyMatch(c->!mode.geometry().contains(c)))
                    throw new IllegalArgumentException("Invalid attack area");
                if(attack.cells().stream().anyMatch(c->shots.get(attacker).containsKey(c)
                    && !shots.get(attacker).get(c).blocked()))
                    throw new IllegalStateException("Cell already targeted");
            }
        }

        ShotImpact last=new ShotImpact(false,null);
        for(AbilityEffect effect:planned.effects()) {
            if(effect instanceof AbilityEffect.Attack attack) {
                for(Cell cell:attack.cells()) {
                    last=resolveShot(attacker,defender,cell);
                    if(((VictoryRule)mode.rules().get(RuleSlot.VICTORY))
                        .defeated(boards.get(defender),successfulShots(attacker)))break;
                }
            } else if(effect instanceof AbilityEffect.Scan scan) {
                radarReports.get(attacker).add(scan.report());
            } else if(effect instanceof AbilityEffect.Shield shield) {
                shields.put(attacker,new ShieldState(shield.shipType(),
                    actionsTaken.get(defender)+2));
            } else if(effect instanceof AbilityEffect.LastChance) {
                lastChanceExpires.put(attacker,actionsTaken.get(defender)+3);
            } else if(effect instanceof AbilityEffect.Jam) {
                jamUntilAction.put(defender,actionsTaken.get(defender)+1);
            } else if(effect instanceof AbilityEffect.Move move) {
                boards.put(attacker,move.newBoard());
            } else {
                throw new IllegalStateException("Unknown server-side effect");
            }
        }
        abilityUseCounts.get(attacker).merge(spec.id(),1,Integer::sum);
        advanceTurn(attacker,defender,last);
    }


    public BattleResponse view(long player) {
        requireParticipant(player);
        long rival=player==hostId?guestId:hostId;
        boolean locked=jamUntilAction.getOrDefault(player,0)>actionsTaken.get(player);
        List<AbilityStatus> available=mode.rules().get(RuleSlot.ABILITY) instanceof AbilityRule rule
            ?rule.catalog().stream().map(spec->new AbilityStatus(spec.id(),spec.name(),
                Math.max(0,spec.maxUses()-abilityUseCounts.get(player).getOrDefault(spec.id(),0)),
                spec.targetCount(),spec.targetScope(),spec.description(),locked)).toList()
            :List.of();
        return new BattleResponse(id,lobbyCode,mode.summary(),
            finished()?"FINISHED":"PLAYING",revision,
            finished()?null:turnPlayerId,winnerId,
            boards.get(player).ships(),List.copyOf(shots.get(player).values()),
            List.copyOf(shots.get(rival).values()),finishReason,
            Map.copyOf(reconnectDeadlines),available,
            List.copyOf(radarReports.get(player)));
    }

    private void requireParticipant(long player) {
        if (player != hostId && player != guestId)
            throw new IllegalArgumentException("Not a participant");
    }
}
