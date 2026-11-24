package com.FGInteractive.BatalhaNaval.matchmaking.service;

import com.FGInteractive.BatalhaNaval.matchmaking.dto.LobbyResponse;
import com.FGInteractive.BatalhaNaval.matchmaking.dto.QueueResponse;
import com.FGInteractive.BatalhaNaval.match.service.MatchPreparationStore;
import com.FGInteractive.BatalhaNaval.match.model.BattleRound;
import com.FGInteractive.BatalhaNaval.match.dto.BattleResponse;
import com.FGInteractive.BatalhaNaval.match.mode.GameModeDefinition;
import com.FGInteractive.BatalhaNaval.match.mode.GddMatchRules;
import com.FGInteractive.BatalhaNaval.match.model.PreparationRound;
import com.FGInteractive.BatalhaNaval.match.mode.GameModeAvailabilityService;
import java.util.function.Function;
import com.FGInteractive.BatalhaNaval.matchmaking.dto.LobbyResponse.LobbyPlayer;
import com.FGInteractive.BatalhaNaval.matchmaking.dto.LobbyResponse.RematchStatus;
import com.FGInteractive.BatalhaNaval.matchmaking.model.LobbyRoom;
import com.FGInteractive.BatalhaNaval.shared.websocket.RealtimePresenceChangedEvent;
import com.FGInteractive.BatalhaNaval.shared.websocket.RealtimeWebSocketHandler;
import com.FGInteractive.BatalhaNaval.user.model.User;
import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single-instance, server-authoritative lobby control plane for the academic MVP.
 * All multi-map mutations and ready/transition checks share one lock.
 */
@Service
public class MatchmakingService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    private static final int MAX_ROOMS = 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Object mutex = new Object();
    private final Map<String, LobbyRoom> rooms = new HashMap<>();
    private final Map<Long, String> membership = new HashMap<>();
    // LinkedHashMap preserves FIFO for each mode without exposing private lobby codes.
    // A searching user has no room membership until an opponent is actually paired.
    private final Map<Long, String> searching = new LinkedHashMap<>();
    private record QueueAssignment(QueueResponse response, LobbyRoom pairedRoom) {}
    private final UserRepository users;
    private final com.FGInteractive.BatalhaNaval.store.service.StoreService store;
    private final RealtimeWebSocketHandler realtime;
    private final MatchPreparationStore preparations;
    private final GameModeAvailabilityService modes;
    private final Duration disconnectGrace;
    private final boolean gddEnforced;

    public MatchmakingService(UserRepository users, RealtimeWebSocketHandler realtime,
                              com.FGInteractive.BatalhaNaval.store.service.StoreService store,
                              MatchPreparationStore preparations, GameModeAvailabilityService modes,
                              @Value("${match.disconnect-grace-seconds:15}") long graceSeconds,
                              @Value("${match.gdd-enforced:true}") boolean gddEnforced) {
        if (graceSeconds < 1 || graceSeconds > 3600)
            throw new IllegalArgumentException("Disconnect grace must be between 1 and 3600 seconds");
        this.users = users;
        this.store = store;
        this.realtime = realtime;
        this.preparations = preparations;
        this.modes = modes;
        this.disconnectGrace = Duration.ofSeconds(graceSeconds);
        this.gddEnforced = gddEnforced;
    }

    public Duration disconnectGrace() { return disconnectGrace; }

    public LobbyResponse create(long userId) { return create(userId, null); }

    public LobbyResponse create(long userId, String modeId) {
        // Availability check and lobby allocation are ordered atomically with
        // administrator toggles. Existing room snapshots never change modes.
        return modes.withEnabledMode(modeId, mode -> {
            LobbyRoom room;
            LobbyResponse response;
            synchronized (mutex) {
                requireFree(userId);
                if (rooms.size() >= MAX_ROOMS) {
                    throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Lobby capacity reached");
                }
                String code = newCode();
                room = new LobbyRoom(code, userId, realtime.isPlayerConnected(userId), mode);
                response = view(room); // Validate user before mutating indexes.
                rooms.put(code, room);
                membership.put(userId, code);
            }
            broadcast(room);
            return response;
        });
    }

    public LobbyResponse join(long userId, String suppliedCode) {
        String code = normalizeCode(suppliedCode);
        LobbyRoom room;
        LobbyResponse view;
        boolean changed = false;
        synchronized (mutex) {
            room = rooms.get(code);
            if (room == null) throw problem(HttpStatus.NOT_FOUND, "Lobby not found");

            String ownCode = membership.get(userId);
            if (ownCode != null) {
                if (ownCode.equals(code)) return view(room); // Idempotent replay.
                throw problem(HttpStatus.CONFLICT, "Leave your current lobby first");
            }
            if (searching.containsKey(userId))
                throw problem(HttpStatus.CONFLICT, "Cancel your public search before joining a room");
            if (room.publicMatch())
                throw problem(HttpStatus.CONFLICT, "Public matches cannot be entered by invitation code");
            if (room.isFull() || room.phase() != LobbyRoom.Phase.WAITING) {
                throw problem(HttpStatus.CONFLICT, "Lobby is full or already preparing");
            }
            // Fail before joining if this user is not present in the account store.
            users.findById(userId).orElseThrow(() -> problem(HttpStatus.UNAUTHORIZED, "Player missing"));
            room.join(userId, realtime.isPlayerConnected(userId));
            membership.put(userId, code);
            view = view(room);
            changed = true;
        }
        if (changed) broadcast(room);
        return view;
    }

    /**
     * Atomic, mode-scoped, FIFO public matchmaking.
     * Players searching have no room and cannot be joined via private codes.
     * The existing ready/placement lifecycle is reused after pairing.
     */
    public QueueResponse findOpponent(long userId, String requestedModeId) {
        QueueAssignment assignment = modes.withEnabledMode(requestedModeId, mode -> {
            synchronized (mutex) {
                String memberCode = membership.get(userId);
                if (memberCode != null) {
                    LobbyRoom existing = rooms.get(memberCode);
                    if (existing != null && existing.publicMatch()
                        && existing.mode().id().equals(mode.id()))
                        return new QueueAssignment(new QueueResponse("MATCHED", mode.id(), view(existing)), null);
                    throw problem(HttpStatus.CONFLICT, "Leave your current lobby first");
                }
                String alreadySearching = searching.get(userId);
                if (alreadySearching != null) {
                    if (alreadySearching.equals(mode.id()))
                        return new QueueAssignment(new QueueResponse("SEARCHING", mode.id(), null), null);
                    throw problem(HttpStatus.CONFLICT, "Cancel your current search before changing modes");
                }

                // Validate the requester before enqueuing them.
                users.findById(userId).orElseThrow(
                    () -> problem(HttpStatus.UNAUTHORIZED, "Player missing"));
                Long opponent = searching.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(userId)
                        && entry.getValue().equals(mode.id())
                        && !membership.containsKey(entry.getKey()))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
                if (opponent == null) {
                    searching.put(userId, mode.id());
                    return new QueueAssignment(new QueueResponse("SEARCHING", mode.id(), null), null);
                }
                if (rooms.size() >= MAX_ROOMS)
                    throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Lobby capacity reached");

                // Validate opponent before mutating queue indexes; all operations share mutex.
                users.findById(opponent).orElseThrow(
                    () -> problem(HttpStatus.CONFLICT, "Queued player is unavailable"));
                LobbyRoom paired = new LobbyRoom(newCode(), opponent,
                    realtime.isPlayerConnected(opponent), mode, true);
                paired.join(userId, realtime.isPlayerConnected(userId));
                // Validate both snapshots before indexing the room (same as private create).
                // Temporarily create membership for rendering, fully under mutex.
                searching.remove(opponent);
                rooms.put(paired.code(), paired);
                membership.put(opponent, paired.code());
                membership.put(userId, paired.code());
                return new QueueAssignment(
                    new QueueResponse("MATCHED", mode.id(), view(paired)), paired);
            }
        });
        if (assignment.pairedRoom() != null) {
            broadcast(assignment.pairedRoom());
            // Idempotent client reconciliation loads the canonical room snapshot.
            realtime.sendToPlayer(assignment.pairedRoom().hostId(), "MATCH_FOUND",
                Map.of("status", "MATCHED"));
            realtime.sendToPlayer(assignment.pairedRoom().guestId(), "MATCH_FOUND",
                Map.of("status", "MATCHED"));
        }
        return assignment.response();
    }

    /** Supports reload/reconnect: in queue, or paired into a public room. */
    public QueueResponse queueStatus(long userId) {
        synchronized (mutex) {
            String code = membership.get(userId);
            if (code != null) {
                LobbyRoom paired = rooms.get(code);
                if (paired != null && paired.publicMatch())
                    return new QueueResponse("MATCHED", paired.mode().id(), view(paired));
                throw problem(HttpStatus.NOT_FOUND, "Player is not searching");
            }
            String mode = searching.get(userId);
            if (mode == null) throw problem(HttpStatus.NOT_FOUND, "Player is not searching");
            return new QueueResponse("SEARCHING", mode, null);
        }
    }

    /**
     * Single authoritative snapshot for the client. Unlike the legacy
     * /lobbies/me and /queue/me lookup routes, being idle is not an error.
     * Covers private rooms, public rooms and code-free matchmaking under
     * the same membership mutex to avoid contradictory 404 snapshots.
     */
    public QueueResponse matchmakingState(long userId) {
        synchronized (mutex) {
            String code = membership.get(userId);
            if (code != null) {
                LobbyRoom room = rooms.get(code);
                if (room != null && room.contains(userId))
                    return new QueueResponse("MATCHED", room.mode().id(), view(room));
            }
            String modeId = searching.get(userId);
            if (modeId != null) return new QueueResponse("SEARCHING", modeId, null);
            return new QueueResponse("IDLE", null, null);
        }
    }

    public void cancelSearch(long userId) {
        synchronized (mutex) {
            if (searching.remove(userId) != null) return;
            String code = membership.get(userId);
            if (code != null && rooms.containsKey(code) && rooms.get(code).publicMatch())
                throw problem(HttpStatus.CONFLICT, "Opponent already found; leave the room instead");
            // Idempotent when user isn't searching.
        }
    }

    public LobbyResponse mine(long userId) {
        synchronized (mutex) {
            return view(myRoom(userId));
        }
    }

    public LobbyResponse ready(long userId, boolean isReady) {
        LobbyRoom room;
        LobbyResponse view;
        boolean changed;
        synchronized (mutex) {
            room = myRoom(userId);
            boolean wasWaiting = room.phase() == LobbyRoom.Phase.WAITING;
            try {
                changed = room.ready(userId, isReady);
            } catch (IllegalStateException ex) {
                throw problem(HttpStatus.CONFLICT, "Lobby is already preparing");
            }
            if (wasWaiting && room.phase() == LobbyRoom.Phase.PREPARING) {
                // Initialize before other threads can submit a fleet.
                if(gddEnforced && GddMatchRules.appliesTo(room.mode()))
                    preparations.start(room.code(),room.hostId(),room.guestId(),
                        room.mode(),Instant.now(),GddMatchRules.PREPARATION);
                else preparations.start(room.code(),room.hostId(),room.guestId(),room.mode());
            }
            view = view(room);
        }
        if (changed) broadcast(room);
        return view;
    }

    /**
     * Accept/withdraw a rematch invitation without creating a public queue entry.
     * Both original players must still be in the finished room. A second acceptance
     * creates an independent round in PREPARING with the SAME players and mode.
     * The old battle/ships are discarded; readiness is not requested twice.
     */
    public LobbyResponse rematch(long userId, boolean accepted) {
        if (!accepted) return voteRematch(userId, false, null);

        // Keep the same lock order as new rooms: mode availability, then lobby mutex.
        String modeId;
        synchronized (mutex) {
            LobbyRoom original = myRoom(userId);
            if (original.phase() != LobbyRoom.Phase.FINISHED)
                throw problem(HttpStatus.CONFLICT, "Finish the battle before requesting a rematch");
            modeId = original.mode().id();
        }
        return modes.withEnabledMode(modeId, mode ->
            voteRematch(userId, true, mode.id()));
    }

    private LobbyResponse voteRematch(long userId, boolean accepted, String expectedMode) {
        LobbyRoom toPublish;
        LobbyResponse response;
        boolean changed;
        synchronized (mutex) {
            LobbyRoom original = myRoom(userId);
            String oldCode = original.code();
            if (original.phase() != LobbyRoom.Phase.FINISHED || original.guestId() == null)
                throw problem(HttpStatus.CONFLICT, "Rematch is only available after a finished battle");
            if (expectedMode != null && !original.mode().id().equals(expectedMode))
                throw problem(HttpStatus.CONFLICT, "The game mode changed");
            if (!oldCode.equals(membership.get(original.hostId()))
                || !oldCode.equals(membership.get(original.guestId()))
                || original.rematchDeclined())
                throw problem(HttpStatus.CONFLICT, "The other player left the result screen");

            try {
                changed = original.voteRematch(userId, accepted);
            } catch (IllegalStateException ex) {
                throw problem(HttpStatus.CONFLICT, ex.getMessage());
            }
            if (accepted && original.bothRematchAccepted()) {
                // Allocate a fresh room/code to ensure a fresh matchId and prevent
                // stale preparation/battle snapshots from crossing between games.
                String freshCode = newCode();
                LobbyRoom next = new LobbyRoom(freshCode, original.hostId(),
                    original.hostConnected(), original.mode(), original.publicMatch());
                next.join(original.guestId(), original.guestConnected());
                var series = rivalry(original);
                next.inheritRivalry(series.hostWins(), series.guestWins(), series.draws());
                next.ready(next.hostId(), true);
                next.ready(next.guestId(), true); // Transitions directly to PREPARING.
                if (gddEnforced && GddMatchRules.appliesTo(next.mode())) {
                    preparations.start(freshCode, next.hostId(), next.guestId(),
                        next.mode(), Instant.now(), GddMatchRules.PREPARATION);
                } else {
                    preparations.start(freshCode, next.hostId(), next.guestId(), next.mode());
                }
                rooms.remove(oldCode);
                preparations.remove(oldCode);
                rooms.put(freshCode, next);
                membership.put(next.hostId(), freshCode);
                membership.put(next.guestId(), freshCode);
                toPublish = next;
                response = view(next);
            } else {
                toPublish = changed ? original : null;
                response = view(original);
            }
        }
        // I/O outside the shared lock, as with normal lobby changes.
        if (toPublish != null) broadcast(toPublish);
        return response;
    }

    /**
     * Leaving during PLAYING is an explicit surrender, but preserves both
     * memberships until the players dismiss the result. Leaving a FINISHED
     * match frees that player; the last departure releases the in-memory room.
     * In WAITING / PREPARING, retain the original lobby semantics.
     */
    public void leave(long userId) {
        LobbyRoom room;
        Long guestToNotify = null;
        boolean guestLeft = false;
        boolean surrendered = false;
        boolean departedFinished = false;
        Long requeuedPlayer = null;
        synchronized (mutex) {
            String code = membership.get(userId);
            if (code == null) {
                // Generic leave must also release queued players (including test cleanup
                // and older clients that use the room endpoint to abandon matchmaking).
                searching.remove(userId);
                return;
            }
            room = rooms.get(code);
            if (room == null) {
                membership.remove(userId);
                return;
            }
            if (room.phase() == LobbyRoom.Phase.PLAYING) {
                BattleRound battle = preparations.battle(code);
                battle.forfeit(userId);
                room.finishBattle();
                surrendered = true;
                // Do not remove user membership: both can inspect final outcome.
            } else if (room.phase() == LobbyRoom.Phase.FINISHED) {
                membership.remove(userId);
                room.departAfterFinish(userId);
                departedFinished = true;
                long other = room.hostId() == userId ? room.guestId() : room.hostId();
                if (!code.equals(membership.get(other))) {
                    rooms.remove(code);
                    preparations.remove(code);
                }
            } else {
                membership.remove(userId);
                preparations.remove(code);
                if (room.publicMatch()) {
                    // A canceled public match is not a private invitation room:
                    // return the remaining user to the queue automatically.
                    Long other = room.hostId() == userId ? room.guestId() : room.hostId();
                    rooms.remove(code);
                    if (other != null) {
                        membership.remove(other);
                        searching.put(other, room.mode().id());
                        requeuedPlayer = other;
                    }
                } else if (room.hostId() == userId) {
                    guestToNotify = room.guestId();
                    rooms.remove(code);
                    if (guestToNotify != null) membership.remove(guestToNotify);
                } else {
                    room.guestLeaves();
                    guestLeft = true;
                }
            }
        }
        if (surrendered) {
            broadcast(room);
            publishBattle(room, null);
            return;
        }
        realtime.sendToPlayer(userId, "LOBBY_LEFT", Map.of());
        if (requeuedPlayer != null)
            realtime.sendToPlayer(requeuedPlayer, "MATCH_SEARCHING", Map.of("status", "SEARCHING"));
        if (guestToNotify != null)
            realtime.sendToPlayer(guestToNotify, "LOBBY_CLOSED", Map.of());
        if (guestLeft || departedFinished) broadcast(room);
    }

    @EventListener
    public void onPresenceChanged(RealtimePresenceChangedEvent event) {
        LobbyRoom room;
        boolean changed;
        LobbyResponse snapshot = null;
        boolean battleStateChanged = false;
        boolean hasBattle = false;
        synchronized (mutex) {
            String code = membership.get(event.userId());
            if (code == null) return;
            room = rooms.get(code);
            if (room == null) return;
            boolean online = realtime.isPlayerConnected(event.userId());
            changed = room.setConnected(event.userId(), online);
            if (room.phase() == LobbyRoom.Phase.PLAYING) {
                hasBattle = true;
                BattleRound battle = preparations.battle(code);
                Instant now = Instant.now();
                // An overdue reconnect cannot erase an already expired
                // deadline merely because the watchdog has not run yet.
                if (battle.resolveDisconnects(now)) {
                    room.finishBattle();
                    battleStateChanged = true;
                } else {
                    battleStateChanged = battle.setPlayerConnected(
                        event.userId(), online, now, disconnectGrace);
                }
            } else if (room.phase() == LobbyRoom.Phase.FINISHED) {
                hasBattle = true;
            }
            if (!changed) snapshot = view(room);
        }
        if (changed || battleStateChanged) broadcast(room);
        else realtime.sendToPlayer(event.userId(), "LOBBY_UPDATED", snapshot);
        // New socket/second tab receives a private match snapshot, not just
        // a lobby update. Deadline changes are also visible to the opponent.
        if (hasBattle) publishBattle(room, (changed || battleStateChanged)
            ? null : event.userId());
    }

    /** Shared adjudication for preparation, reconnect, turn and match clocks.
     * No socket I/O is performed under the lobby mutex.
     */
    @Scheduled(fixedDelayString = "${match.game-sweep-ms:500}")
    public void sweepGameRules() {
        sweepGameRulesAt(Instant.now());
    }

    public void sweepGameRulesAt(Instant now) {
        List<LobbyRoom> started = new ArrayList<>();
        List<LobbyRoom> battleChanges = new ArrayList<>();
        List<LobbyRoom> finished = new ArrayList<>();
        synchronized(mutex) {
            for(LobbyRoom room : rooms.values()) {
                if(room.phase()==LobbyRoom.Phase.PREPARING) {
                    PreparationRound preparation=preparations.get(room.code());
                    if(preparation.completeExpired(now)) {
                        startBattleLocked(room,now);
                        started.add(room);
                    }
                } else if(room.phase()==LobbyRoom.Phase.PLAYING) {
                    BattleRound battle=preparations.battle(room.code());
                    boolean changed=battle.resolveDisconnects(now);
                    if(!changed)changed=battle.resolveTimed(now);
                    if(changed) {
                        if(battle.finished()) {
                            room.finishBattle();
                            finished.add(room);
                        }
                        battleChanges.add(room);
                    }
                }
            }
        }
        for(LobbyRoom room : started) {
            broadcast(room);
            publishPreparation(room);
            publishBattle(room,null);
        }
        for(LobbyRoom room : battleChanges) {
            if(finished.contains(room))broadcast(room);
            publishBattle(room,null);
        }
    }

    /** Must be called under the shared lobby mutex. */
    public BattleRound startBattleLocked(LobbyRoom room,Instant now) {
        if(room.phase()!=LobbyRoom.Phase.PREPARING)
            throw new IllegalStateException("Room is not preparing");
        PreparationRound preparation=preparations.get(room.code());
        if(!preparation.bothConfirmed())
            throw new IllegalStateException("Both fleets must be confirmed");
        boolean timed = gddEnforced && GddMatchRules.appliesTo(room.mode());
        BattleRound battle=timed ?
            new BattleRound(room.code(),room.mode(),room.hostId(),room.guestId(),
                preparation.confirmedBoard(room.hostId()),
                preparation.confirmedBoard(room.guestId()),now,
                GddMatchRules.TURN,GddMatchRules.MATCH) :
            new BattleRound(room.code(),room.mode(),room.hostId(),room.guestId(),
                preparation.confirmedBoard(room.hostId()),
                preparation.confirmedBoard(room.guestId()));
        preparations.startBattle(room.code(),battle);
        room.startBattle();
        battle.setPlayerConnected(room.hostId(),room.hostConnected(),now,disconnectGrace);
        battle.setPlayerConnected(room.guestId(),room.guestConnected(),now,disconnectGrace);
        return battle;
    }

    private void publishPreparation(LobbyRoom room) {
        com.FGInteractive.BatalhaNaval.match.dto.PreparationResponse host;
        com.FGInteractive.BatalhaNaval.match.dto.PreparationResponse guest;
        synchronized(mutex) {
            if(rooms.get(room.code())!=room)return;
            PreparationRound round=preparations.get(room.code());
            host=round.view(room.hostId());
            guest=round.view(room.guestId());
        }
        realtime.sendToPlayer(room.hostId(),"PREPARATION_UPDATED",host);
        realtime.sendToPlayer(room.guestId(),"PREPARATION_UPDATED",guest);
    }

    /** Cron only adjudicates expired deadlines; event callbacks cancel them. */
    @Scheduled(fixedDelayString = "${match.disconnect-sweep-ms:5000}")
    public void sweepDisconnected() {
        sweepDisconnectedAt(Instant.now());
    }

    /** Explicit clock makes timeout races testable without sleeping. */
    public void sweepDisconnectedAt(Instant now) {
        List<LobbyRoom> resolved = new ArrayList<>();
        synchronized (mutex) {
            for (LobbyRoom room : rooms.values()) {
                if (room.phase() != LobbyRoom.Phase.PLAYING) continue;
                if (preparations.battle(room.code()).resolveDisconnects(now)) {
                    room.finishBattle();
                    resolved.add(room);
                }
            }
        }
        for (LobbyRoom room : resolved) {
            broadcast(room);
            publishBattle(room, null);
        }
    }

    private void publishBattle(LobbyRoom room, Long onlyPlayer) {
        BattleResponse host;
        BattleResponse guest;
        boolean sendHost;
        boolean sendGuest;
        synchronized (mutex) {
            if (rooms.get(room.code()) != room) return;
            BattleRound battle = preparations.battle(room.code());
            host = battle.view(room.hostId());
            guest = battle.view(room.guestId());
            sendHost = (onlyPlayer == null || room.hostId() == onlyPlayer)
                && room.code().equals(membership.get(room.hostId()));
            sendGuest = (onlyPlayer == null || room.guestId() == onlyPlayer)
                && room.code().equals(membership.get(room.guestId()));
        }
        if (sendHost) realtime.sendToPlayer(room.hostId(), "BATTLE_UPDATED", host);
        if (sendGuest) realtime.sendToPlayer(room.guestId(), "BATTLE_UPDATED", guest);
    }

    private void broadcast(LobbyRoom room) {
        LobbyResponse view;
        List<Long> participants;
        synchronized (mutex) {
            if (rooms.get(room.code()) != room) return;
            view = view(room);
            participants = room.participants().stream()
                .filter(id -> room.code().equals(membership.get(id))).toList();
        }
        // Never perform socket I/O under the room lock. Revisions let clients
        // discard late updates if two broadcasts race after unlocking.
        for (Long participant : participants) {
            realtime.sendToPlayer(participant, "LOBBY_UPDATED", view);
        }
    }

    /**
     * All game-preparation operations run under the same mutex as lobby
     * membership changes and ready transitions. Prevents leave/rejoin races.
     */
    public <T> T withPreparingRoom(long userId, Function<LobbyRoom, T> action) {
        synchronized (mutex) {
            LobbyRoom room = myRoom(userId);
            if (room.phase() != LobbyRoom.Phase.PREPARING || room.guestId() == null) {
                throw problem(HttpStatus.CONFLICT, "Lobby is not preparing");
            }
            return action.apply(room);
        }
    }

    /** Like withPreparingRoom, but allows read-only access to active/final rounds. */
    public <T> T withActiveRoom(long userId, Function<LobbyRoom, T> action) {
        synchronized (mutex) {
            LobbyRoom room = myRoom(userId);
            if (room.phase() == LobbyRoom.Phase.WAITING || room.guestId() == null)
                throw problem(HttpStatus.CONFLICT, "Lobby is not preparing or playing");
            return action.apply(room);
        }
    }

    public <T> T withBattleRoom(long userId, Function<LobbyRoom, T> action) {
        synchronized (mutex) {
            LobbyRoom room = myRoom(userId);
            if (room.phase() != LobbyRoom.Phase.PLAYING
                && room.phase() != LobbyRoom.Phase.FINISHED)
                throw problem(HttpStatus.CONFLICT, "Battle has not started");
            return action.apply(room);
        }
    }

    /** Snapshot broadcast occurs outside mutex; revision prevents stale UI updates. */
    public void publishRoomFor(long userId) {
        LobbyRoom room;
        synchronized (mutex) { room = myRoom(userId); }
        broadcast(room);
    }

    /** The current finished round is included exactly once, without mutating the score. */
    private LobbyResponse.RivalryStatus rivalry(LobbyRoom room) {
        int host=room.priorHostWins(), guest=room.priorGuestWins(), draws=room.priorDraws();
        if (room.phase() == LobbyRoom.Phase.FINISHED) {
            Long winner=preparations.battle(room.code()).view(room.hostId()).winnerId();
            if (winner == null) draws++;
            else if (winner.longValue()==room.hostId()) host++;
            else if (Objects.equals(winner,room.guestId())) guest++;
        }
        return new LobbyResponse.RivalryStatus(host,guest,draws);
    }

    private LobbyResponse view(LobbyRoom room) {
        List<LobbyPlayer> players = new ArrayList<>(2);
        players.add(player(room.hostId(), room.hostReady(),
            room.hostConnected() && room.code().equals(membership.get(room.hostId()))));
        if (room.guestId() != null) {
            players.add(player(room.guestId(), room.guestReady(),
                room.guestConnected() && room.code().equals(membership.get(room.guestId()))));
        }
        return new LobbyResponse(room.code(), room.phase().name(), room.revision(), 2,
            List.copyOf(players), room.mode().summary(), room.publicMatch(),
            room.phase() == LobbyRoom.Phase.FINISHED
                ? new RematchStatus(room.hostRematchAccepted(), room.guestRematchAccepted(),
                    room.code().equals(membership.get(room.hostId()))
                        && room.code().equals(membership.get(room.guestId()))
                        && !room.rematchDeclined(),
                    room.rematchDeclined())
                : null, rivalry(room));
    }

    private LobbyPlayer player(long userId, boolean ready, boolean connected) {
        User user = users.findById(userId)
            .orElseThrow(() -> problem(HttpStatus.UNAUTHORIZED, "Player missing"));
        return new LobbyPlayer(user.getId(), user.getNickname(), user.getAvatarId(), ready, connected,store.equippedPresentation(userId));
    }

    private LobbyRoom myRoom(long userId) {
        String code = membership.get(userId);
        if (code == null) throw problem(HttpStatus.NOT_FOUND, "Player is not in a lobby");
        LobbyRoom room = rooms.get(code);
        if (room == null || !room.contains(userId)) {
            throw problem(HttpStatus.NOT_FOUND, "Player is not in a lobby");
        }
        return room;
    }

    private void requireFree(long userId) {
        if (membership.containsKey(userId) || searching.containsKey(userId)) {
            throw problem(HttpStatus.CONFLICT, "Player is already in a lobby or search");
        }
    }

    private String newCode() {
        for (int tries = 0; tries < 30; tries++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String result = code.toString();
            if (!rooms.containsKey(result)) return result;
        }
        throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Could not allocate lobby code");
    }

    private String normalizeCode(String code) {
        if (code == null) throw problem(HttpStatus.BAD_REQUEST, "Lobby code is required");
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != CODE_LENGTH
            || !normalized.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0)) {
            throw problem(HttpStatus.BAD_REQUEST, "Invalid lobby code");
        }
        return normalized;
    }

    private static ResponseStatusException problem(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
