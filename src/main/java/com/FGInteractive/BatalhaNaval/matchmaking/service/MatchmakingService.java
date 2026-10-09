package com.FGInteractive.BatalhaNaval.matchmaking.service;

import com.FGInteractive.BatalhaNaval.matchmaking.dto.LobbyResponse;
import com.FGInteractive.BatalhaNaval.match.service.MatchPreparationStore;
import java.util.function.Function;
import com.FGInteractive.BatalhaNaval.matchmaking.dto.LobbyResponse.LobbyPlayer;
import com.FGInteractive.BatalhaNaval.matchmaking.model.LobbyRoom;
import com.FGInteractive.BatalhaNaval.shared.websocket.RealtimePresenceChangedEvent;
import com.FGInteractive.BatalhaNaval.shared.websocket.RealtimeWebSocketHandler;
import com.FGInteractive.BatalhaNaval.user.model.User;
import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.event.EventListener;
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

    public MatchmakingService(UserRepository users, RealtimeWebSocketHandler realtime,
                              MatchPreparationStore preparations) {
        this.users = users;
        this.realtime = realtime;
        this.preparations = preparations;
    }

    public LobbyResponse create(long userId) {
        LobbyRoom room;
        LobbyResponse view;
        synchronized (mutex) {
            requireFree(userId);
            if (rooms.size() >= MAX_ROOMS) {
                throw problem(HttpStatus.SERVICE_UNAVAILABLE, "Lobby capacity reached");
            }
            String code = newCode();
            room = new LobbyRoom(code, userId, realtime.isPlayerConnected(userId));
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
                preparations.start(room.code(), room.hostId(), room.guestId());
            }
            view = view(room);
        }
        if (changed) broadcast(room);
        return view;
    }

    public void leave(long userId) {
        LobbyRoom room;
        Long guestToNotify = null;
        boolean guestLeft = false;
        synchronized (mutex) {
            String code = membership.get(userId);
            if (code == null) return; // Idempotent DELETE.
            room = rooms.get(code);
            membership.remove(userId);
            if (room == null) return;
            // Guest departures also clear an in-progress board before host can
            // ready-up with a different opponent.
            preparations.remove(room.code());
            if (room.hostId() == userId) {
                guestToNotify = room.guestId();
                rooms.remove(code);
                if (guestToNotify != null) membership.remove(guestToNotify);
            } else {
                room.guestLeaves();
                guestLeft = true;
            }
        }
        realtime.sendToPlayer(userId, "LOBBY_LEFT", Map.of());
        if (guestToNotify != null) realtime.sendToPlayer(guestToNotify, "LOBBY_CLOSED", Map.of());
        if (guestLeft) broadcast(room);
    }

    @EventListener
    public void onPresenceChanged(RealtimePresenceChangedEvent event) {
        LobbyRoom room;
        boolean changed;
        LobbyResponse snapshot = null;
        synchronized (mutex) {
            String code = membership.get(event.userId());
            if (code == null) return;
            room = rooms.get(code);
            if (room == null) return;
            changed = room.setConnected(event.userId(),
                realtime.isPlayerConnected(event.userId()));
            // A second tab must receive its initial snapshot even if the
            // account was already online from another tab.
            if (!changed) snapshot = view(room);
        }
        if (changed) {
            broadcast(room);
        } else {
            realtime.sendToPlayer(event.userId(), "LOBBY_UPDATED", snapshot);
        }
    }

    private void broadcast(LobbyRoom room) {
        LobbyResponse view;
        List<Long> participants;
        synchronized (mutex) {
            if (rooms.get(room.code()) != room) return;
            view = view(room);
            participants = room.participants();
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

    private LobbyResponse view(LobbyRoom room) {
        List<LobbyPlayer> players = new ArrayList<>(2);
        players.add(player(room.hostId(), room.hostReady(), room.hostConnected()));
        if (room.guestId() != null) {
            players.add(player(room.guestId(), room.guestReady(), room.guestConnected()));
        }
        return new LobbyResponse(room.code(), room.phase().name(), room.revision(), 2,
            List.copyOf(players));
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
