package com.FGInteractive.BatalhaNaval.matchmaking.service;

import com.FGInteractive.BatalhaNaval.matchmaking.dto.LobbyResponse;
import com.FGInteractive.BatalhaNaval.match.service.MatchPreparationStore;
import com.FGInteractive.BatalhaNaval.match.model.BattleRound;
import com.FGInteractive.BatalhaNaval.match.dto.BattleResponse;
import com.FGInteractive.BatalhaNaval.match.mode.GameModeDefinition;
import com.FGInteractive.BatalhaNaval.match.mode.GameModeRegistry;
import java.util.function.Function;
import com.FGInteractive.BatalhaNaval.matchmaking.dto.LobbyResponse.LobbyPlayer;
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
    private final UserRepository users;
    private final RealtimeWebSocketHandler realtime;
    private final MatchPreparationStore preparations;
    private final GameModeRegistry modes;
    private final Duration disconnectGrace;

    public MatchmakingService(UserRepository users, RealtimeWebSocketHandler realtime,
                              MatchPreparationStore preparations, GameModeRegistry modes,
                              @Value("${match.disconnect-grace-seconds:120}") long graceSeconds) {
        if (graceSeconds < 1 || graceSeconds > 3600)
            throw new IllegalArgumentException("Disconnect grace must be between 1 and 3600 seconds");
        this.users = users;
        this.realtime = realtime;
        this.preparations = preparations;
        this.modes = modes;
        this.disconnectGrace = Duration.ofSeconds(graceSeconds);
    }

    public Duration disconnectGrace() { return disconnectGrace; }

    public LobbyResponse create(long userId) { return create(userId, null); }

    public LobbyResponse create(long userId, String modeId) {
        // Unknown mode must fail before room allocation or member indexing.
        GameModeDefinition mode = modes.get(modeId);
        LobbyRoom room;
        LobbyResponse view;
        synchronized (mutex) {
            requireFree(userId);
            if (rooms.size() >= MAX_ROOMS) {
                throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Lobby capacity reached");
            }
            String code = newCode();
            room = new LobbyRoom(code, userId, realtime.isPlayerConnected(userId), mode);
            view = view(room); // Check player existence before mutating the indexes.
            rooms.put(code, room);
            membership.put(userId, code);
        }
        broadcast(room);
        return view;
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
                preparations.start(room.code(), room.hostId(), room.guestId(), room.mode());
            }
            view = view(room);
        }
        if (changed) broadcast(room);
        return view;
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
        synchronized (mutex) {
            String code = membership.get(userId);
            if (code == null) return;
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
                if (room.hostId() == userId) {
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
        if (changed) broadcast(room);
        else realtime.sendToPlayer(event.userId(), "LOBBY_UPDATED", snapshot);
        // New socket/second tab receives a private match snapshot, not just
        // a lobby update. Deadline changes are also visible to the opponent.
        if (hasBattle) publishBattle(room, (changed || battleStateChanged)
            ? null : event.userId());
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

    private LobbyResponse view(LobbyRoom room) {
        List<LobbyPlayer> players = new ArrayList<>(2);
        players.add(player(room.hostId(), room.hostReady(),
            room.hostConnected() && room.code().equals(membership.get(room.hostId()))));
        if (room.guestId() != null) {
            players.add(player(room.guestId(), room.guestReady(),
                room.guestConnected() && room.code().equals(membership.get(room.guestId()))));
        }
        return new LobbyResponse(room.code(), room.phase().name(), room.revision(), 2,
            List.copyOf(players), room.mode().summary());
    }

    private LobbyPlayer player(long userId, boolean ready, boolean connected) {
        User user = users.findById(userId)
            .orElseThrow(() -> problem(HttpStatus.UNAUTHORIZED, "Player missing"));
        return new LobbyPlayer(user.getId(), user.getNickname(), user.getAvatarId(), ready, connected);
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
        if (membership.containsKey(userId)) {
            throw problem(HttpStatus.CONFLICT, "Player is already in a lobby");
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
