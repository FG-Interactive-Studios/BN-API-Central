package com.FGInteractive.BatalhaNaval.match.model;

import com.FGInteractive.BatalhaNaval.match.dto.BattleResponse;
import com.FGInteractive.BatalhaNaval.match.dto.AbilityStatus;
import com.FGInteractive.BatalhaNaval.match.dto.RadarReport;
import com.FGInteractive.BatalhaNaval.match.dto.ActiveEffectStatus;
import com.FGInteractive.BatalhaNaval.match.mode.*;
import java.util.*;
import java.time.Duration;
import java.time.Instant;
import java.security.SecureRandom;

/** In-memory, server-authoritative match. All access under the lobby mutex. */
public final class BattleRound {
    private final UUID id = UUID.randomUUID();
    private final String lobbyCode;
    private final GameModeDefinition mode;
    private final long hostId;
    private final long guestId;
    private final Map<Long, Board> boards;
    private final Map<Long, LinkedHashMap<Cell, ShotRecord>> shots = new HashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<Long, Set<Cell>> abilityPointCells = new HashMap<>();
    private final Map<Long, Integer> earnedAbilityPoints = new HashMap<>();
    private final Map<Long, Integer> consecutiveMissedTurns = new HashMap<>();
    private final boolean timed;
    private final Duration turnLimit;
    private Instant turnDeadlineAt;
    private Instant matchDeadlineAt;
    private Instant pausedSince;

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

    /** Legacy constructor for isolated module tests; server game creation
     * uses the timed constructor for the two initial GDD game modes.
     */
    public BattleRound(String lobbyCode, GameModeDefinition mode, long hostId, long guestId,
                       Board hostBoard, Board guestBoard) {
        this(lobbyCode,mode,hostId,guestId,hostBoard,guestBoard,null,null,null,hostId);
    }

    public BattleRound(String lobbyCode, GameModeDefinition mode, long hostId, long guestId,
                       Board hostBoard, Board guestBoard, Instant startedAt,
                       Duration turnLimit, Duration matchLimit) {
        this(lobbyCode,mode,hostId,guestId,hostBoard,guestBoard,
            startedAt,turnLimit,matchLimit,RANDOM.nextBoolean()?hostId:guestId);
    }

    /** Explicit opening player is useful for deterministic tests.
     * Production delegates here with a secure server-side coin toss.
     */
    public BattleRound(String lobbyCode, GameModeDefinition mode, long hostId, long guestId,
                       Board hostBoard, Board guestBoard, Instant startedAt,
                       Duration turnLimit, Duration matchLimit, long openingPlayer) {
        if(openingPlayer!=hostId && openingPlayer!=guestId)
            throw new IllegalArgumentException("Opening player must be a participant");
        if (hostId == guestId) throw new IllegalArgumentException("Two unique players are required");
        if ((startedAt == null) != (turnLimit == null)
            || (startedAt == null) != (matchLimit == null))
            throw new IllegalArgumentException("Timed game requires all clocks");
        if (turnLimit != null && (turnLimit.isNegative() || turnLimit.isZero()
            || matchLimit.isNegative() || matchLimit.isZero()))
            throw new IllegalArgumentException("Timings must be positive");
        timed = startedAt != null;
        this.turnLimit = turnLimit;
        turnDeadlineAt = timed ? startedAt.plus(turnLimit) : null;
        matchDeadlineAt = timed ? startedAt.plus(matchLimit) : null;
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
        turnPlayerId = openingPlayer;
        earnedAbilityPoints.put(hostId,0);
        earnedAbilityPoints.put(guestId,0);
        consecutiveMissedTurns.put(hostId,0);
        consecutiveMissedTurns.put(guestId,0);
        if(timed && mode.rules().get(RuleSlot.ABILITY) instanceof AbilityRule) {
            abilityPointCells.put(hostId,allocatePoints(hostBoard));
            abilityPointCells.put(guestId,allocatePoints(guestBoard));
        }
    }

    private Set<Cell> allocatePoints(Board board) {
        var candidates = new ArrayList<>(mode.geometry().cells());
        candidates.removeAll(board.occupied());
        if(candidates.size() < GddMatchRules.POWER_POINTS_PER_BOARD)
            throw new IllegalArgumentException("Not enough empty cells for ability points");
        Collections.shuffle(candidates,RANDOM);
        return Set.copyOf(candidates.subList(0,GddMatchRules.POWER_POINTS_PER_BOARD));
    }

    /** Only owner sees their board's point coordinates. */
    public Set<Cell> pointsOnOwnBoard(long player) {
        requireParticipant(player);
        return abilityPointCells.getOrDefault(player,Set.of());
    }
    public Instant turnDeadlineAt() { return finished()?null:turnDeadlineAt; }
    public Instant matchDeadlineAt() { return finished()?null:matchDeadlineAt; }
    public boolean paused() { return pausedSince != null; }
    public int missedTurns(long player) {
        requireParticipant(player);
        return consecutiveMissedTurns.getOrDefault(player,0);
    }

    /** Pause wall-clock deadlines during a live disconnection, then extend
     * both by the actual pause duration. Timers never run while paused.
     */
    private void updatePause(Instant now) {
        if(!timed) return;
        if(!reconnectDeadlines.isEmpty() && pausedSince == null) pausedSince = now;
        else if(reconnectDeadlines.isEmpty() && pausedSince != null) {
            Duration suspended = Duration.between(pausedSince,now);
            if(suspended.isNegative()) throw new IllegalArgumentException("Non-monotonic clock");
            turnDeadlineAt = turnDeadlineAt.plus(suspended);
            matchDeadlineAt = matchDeadlineAt.plus(suspended);
            pausedSince = null;
        }
    }

    /** Called by watchdog and before commands under the lobby mutex.
     * One turn is adjudicated per pass to bound a delayed sweep.
     */
    public boolean resolveTimed(Instant now) {
        if (!timed || finished() || paused()) return false;
        if (!now.isBefore(matchDeadlineAt)) {
            int hostAlive = survivingShips(hostId);
            int guestAlive = survivingShips(guestId);
            finish(hostAlive == guestAlive ? null : hostAlive > guestAlive ? hostId : guestId,
                hostAlive == guestAlive ? "TIME_LIMIT_DRAW" : "TIME_LIMIT");
            return true;
        }
        if (now.isBefore(turnDeadlineAt)) return false;
        long absent=turnPlayerId;
        long other=absent==hostId?guestId:hostId;
        int missed = consecutiveMissedTurns.merge(absent,1,Integer::sum);
        if (missed >= GddMatchRules.MAX_CONSECUTIVE_TURN_TIMEOUTS) {
            finish(other,"TURN_TIMEOUT_FORFEIT");
        } else {
            actionsTaken.merge(absent,1,Integer::sum);
            turnPlayerId=other;
            turnDeadlineAt=now.plus(turnLimit);
            revision++;
        }
        return true;
    }

    private int survivingShips(long defender) {
        long attacker=defender==hostId?guestId:hostId;
        Set<Cell> hits=successfulShots(attacker);
        int surviving=0;
        for(var ship:boards.get(defender).ships()) {
            var occupied=mode.placement().footprint(ship,
                mode.fleet().require(ship.type()),mode.geometry());
            if(!hits.containsAll(occupied)) surviving++;
        }
        return surviving;
    }

    private void requirePlayableAt(Instant now) {
        if(!timed) return;
        if(paused()) throw new IllegalStateException("Game is paused during reconnection");
        if(!now.isBefore(turnDeadlineAt) || !now.isBefore(matchDeadlineAt))
            throw new IllegalStateException("Game deadline has expired");
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
        updatePause(now);
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
        // Ability points are on empty cells, so a valid ordinary miss may
        // grant one point. Blocked attempts never grant currency.
        if(timed && abilityPointCells.getOrDefault(defender,Set.of()).contains(target))
            earnedAbilityPoints.merge(attacker,1,Integer::sum);
        return impact;
    }

    private Set<Cell> successfulShots(long player) {
        Set<Cell> cells=new HashSet<>();
        shots.get(player).forEach((cell,record)->{if(!record.blocked())cells.add(cell);});
        return Set.copyOf(cells);
    }

    private void advanceTurn(long attacker,long defender,ShotImpact impact,Instant now) {
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
        if(timed) turnDeadlineAt=now.plus(turnLimit);
        consecutiveMissedTurns.put(attacker,0);
        actionsTaken.merge(attacker,1,Integer::sum);
        revision++;
    }

    public void fire(long attacker, Cell target) {
        fireAt(attacker,target,Instant.now());
    }
    public void fireAt(long attacker,Cell target,Instant now) {
        requireParticipant(attacker);
        if(finished()) throw new IllegalStateException("Match already finished");
        requirePlayableAt(now);
        if(turnPlayerId!=attacker) throw new IllegalStateException("Not your turn");
        if(!mode.geometry().contains(target))
            throw new IllegalArgumentException("Target is outside the playable board");
        var fired=shots.get(attacker);
        if(fired.containsKey(target)&&!fired.get(target).blocked())
            throw new IllegalStateException("Cell already targeted");
        long defender=attacker==hostId?guestId:hostId;
        ShotImpact impact=resolveShot(attacker,defender,target);
        advanceTurn(attacker,defender,impact,now);
    }

    public void useAbility(long attacker,String abilityId,List<Cell> targets) {
        useAbilityAt(attacker,abilityId,targets,Instant.now());
    }
    public void useAbilityAt(long attacker,String abilityId,List<Cell> targets,Instant now) {
        requireParticipant(attacker);
        if(finished()) throw new IllegalStateException("Match already finished");
        requirePlayableAt(now);
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
        boolean free = spec.id().equals("double-shot") || spec.id().equals("radar");
        if(timed && !free && earnedAbilityPoints.get(attacker)<1)
            throw new IllegalStateException("Earn ability points before using this power");

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
                jamUntilAction.put(defender,actionsTaken.get(defender)+2);
            } else if(effect instanceof AbilityEffect.Move move) {
                boards.put(attacker,move.newBoard());
            } else {
                throw new IllegalStateException("Unknown server-side effect");
            }
        }
        abilityUseCounts.get(attacker).merge(spec.id(),1,Integer::sum);
        if(timed && !free) earnedAbilityPoints.merge(attacker,-1,Integer::sum);
        advanceTurn(attacker,defender,last,now);
    }


    public BattleResponse view(long player) {
        requireParticipant(player);
        long rival=player==hostId?guestId:hostId;
        boolean locked=jamUntilAction.getOrDefault(player,0)>actionsTaken.get(player);
        List<AbilityStatus> available=mode.rules().get(RuleSlot.ABILITY) instanceof AbilityRule rule
            ?rule.catalog().stream().map(spec->new AbilityStatus(spec.id(),spec.name(),
                Math.max(0,spec.maxUses()-abilityUseCounts.get(player).getOrDefault(spec.id(),0)),
                spec.targetCount(),spec.targetScope(),spec.description(),
                locked || (timed && !List.of("double-shot","radar").contains(spec.id())
                    && earnedAbilityPoints.get(player)<1))).toList()
            :List.of();
        List<ActiveEffectStatus> effects=new ArrayList<>();
        ShieldState shield=shields.get(player);
        if(shield!=null && shield.expiresAfterEnemyAction()>actionsTaken.get(rival))
            effects.add(new ActiveEffectStatus("hull-shield",
                "Escudo de casco: "+shield.shipType(),
                shield.expiresAfterEnemyAction()-actionsTaken.get(rival)));
        int lastChanceRemaining=lastChanceExpires.getOrDefault(player,0)-actionsTaken.get(rival);
        if(lastChanceRemaining>0)
            effects.add(new ActiveEffectStatus("last-chance","Última chance",lastChanceRemaining));
        int jamRemaining=jamUntilAction.getOrDefault(player,0)-actionsTaken.get(player);
        if(jamRemaining>0)
            effects.add(new ActiveEffectStatus("interference","Interferência recebida",jamRemaining));
        return new BattleResponse(id,lobbyCode,mode.summary(),
            finished()?"FINISHED":"PLAYING",revision,
            finished()?null:turnPlayerId,winnerId,
            boards.get(player).ships(),List.copyOf(shots.get(player).values()),
            List.copyOf(shots.get(rival).values()),finishReason,
            Map.copyOf(reconnectDeadlines),available,
            List.copyOf(radarReports.get(player)),List.copyOf(effects),
            turnDeadlineAt(),matchDeadlineAt(),paused(),
            consecutiveMissedTurns.get(player),earnedAbilityPoints.get(player),
            List.copyOf(abilityPointCells.getOrDefault(player,Set.of())));
    }

    private void requireParticipant(long player) {
        if (player != hostId && player != guestId)
            throw new IllegalArgumentException("Not a participant");
    }
}
