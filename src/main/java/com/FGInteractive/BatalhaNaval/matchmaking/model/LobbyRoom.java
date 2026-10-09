package com.FGInteractive.BatalhaNaval.matchmaking.model;

import java.util.List;
import com.FGInteractive.BatalhaNaval.match.mode.GameModeDefinition;
import java.util.Objects;

/**
 * Mutable in-memory room. All reads/mutations are protected by MatchmakingService's lock.
 * Active game boards are not stored here.
 */
public final class LobbyRoom {
    public enum Phase { WAITING, PREPARING }

    private final String code;
    private final long hostId;
    private final GameModeDefinition mode;
    private Long guestId;
    private boolean hostReady;
    private boolean guestReady;
    private boolean hostConnected;
    private boolean guestConnected;
    private Phase phase = Phase.WAITING;
    private long revision = 1;

    public LobbyRoom(String code, long hostId, boolean hostConnected, GameModeDefinition mode) {
        this.code = code;
        this.hostId = hostId;
        this.hostConnected = hostConnected;
        this.mode = java.util.Objects.requireNonNull(mode);
    }

    public String code() { return code; }
    public long hostId() { return hostId; }
    public GameModeDefinition mode() { return mode; }
    public Long guestId() { return guestId; }
    public boolean hostReady() { return hostReady; }
    public boolean guestReady() { return guestReady; }
    public boolean hostConnected() { return hostConnected; }
    public boolean guestConnected() { return guestConnected; }
    public Phase phase() { return phase; }
    public long revision() { return revision; }
    public boolean isFull() { return guestId != null; }
    public boolean contains(long userId) {
        return hostId == userId || Objects.equals(guestId, userId);
    }
    public List<Long> participants() {
        return guestId == null ? List.of(hostId) : List.of(hostId, guestId);
    }

    public void join(long userId, boolean connected) {
        if (isFull() || phase != Phase.WAITING || contains(userId)) {
            throw new IllegalStateException("Room cannot accept another player");
        }
        guestId = userId;
        guestConnected = connected;
        hostReady = false;
        guestReady = false;
        revision++;
    }

    /** Returns true only when state actually changes. */
    public boolean ready(long userId, boolean ready) {
        if (phase != Phase.WAITING) {
            if (this.hostId == userId && hostReady == ready) return false;
            if (Objects.equals(guestId, userId) && guestReady == ready) return false;
            throw new IllegalStateException("Room already preparing");
        }
        boolean changed;
        if (userId == hostId) {
            changed = hostReady != ready;
            hostReady = ready;
        } else if (Objects.equals(guestId, userId)) {
            changed = guestReady != ready;
            guestReady = ready;
        } else {
            throw new IllegalArgumentException("Not a room participant");
        }
        if (changed) {
            if (guestId != null && hostReady && guestReady) phase = Phase.PREPARING;
            revision++;
        }
        return changed;
    }

    /** A guest leaving reopens the same room and clears readiness. */
    public void guestLeaves() {
        guestId = null;
        guestReady = false;
        guestConnected = false;
        hostReady = false;
        phase = Phase.WAITING;
        revision++;
    }

    public boolean setConnected(long userId, boolean connected) {
        if (userId == hostId) {
            if (hostConnected == connected) return false;
            hostConnected = connected;
        } else if (Objects.equals(guestId, userId)) {
            if (guestConnected == connected) return false;
            guestConnected = connected;
        } else {
            return false;
        }
        revision++;
        return true;
    }
}
