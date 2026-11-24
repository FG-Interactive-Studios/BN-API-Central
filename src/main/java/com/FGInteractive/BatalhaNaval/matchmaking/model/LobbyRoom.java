package com.FGInteractive.BatalhaNaval.matchmaking.model;

import java.util.List;
import com.FGInteractive.BatalhaNaval.match.mode.GameModeDefinition;
import java.util.Objects;

/**
 * Mutable in-memory room. All reads/mutations are protected by MatchmakingService's lock.
 * Active game boards are not stored here.
 */
public final class LobbyRoom {
    public enum Phase { WAITING, PREPARING, PLAYING, FINISHED }

    private final String code;
    private final long hostId;
    private final GameModeDefinition mode;
    private final boolean publicMatch;
    private Long guestId;
    private boolean hostReady;
    private boolean guestReady;
    private boolean hostConnected;
    private boolean guestConnected;
    private boolean hostRematchAccepted;
    private boolean guestRematchAccepted;
    private boolean rematchDeclined;
    private Phase phase = Phase.WAITING;
    private long revision = 1;

    public LobbyRoom(String code, long hostId, boolean hostConnected, GameModeDefinition mode) {
        this(code, hostId, hostConnected, mode, false);
    }

    public LobbyRoom(String code, long hostId, boolean hostConnected, GameModeDefinition mode,
                     boolean publicMatch) {
        this.code = code;
        this.hostId = hostId;
        this.hostConnected = hostConnected;
        this.mode = java.util.Objects.requireNonNull(mode);
        this.publicMatch = publicMatch;
    }

    public String code() { return code; }
    public long hostId() { return hostId; }
    public GameModeDefinition mode() { return mode; }
    public boolean publicMatch() { return publicMatch; }
    public Long guestId() { return guestId; }
    public boolean hostReady() { return hostReady; }
    public boolean guestReady() { return guestReady; }
    public boolean hostConnected() { return hostConnected; }
    public boolean guestConnected() { return guestConnected; }
    public boolean hostRematchAccepted() { return hostRematchAccepted; }
    public boolean guestRematchAccepted() { return guestRematchAccepted; }
    public boolean rematchDeclined() { return rematchDeclined; }
    public boolean bothRematchAccepted() { return hostRematchAccepted && guestRematchAccepted; }
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

    public void startBattle() {
        if (phase != Phase.PREPARING || !isFull())
            throw new IllegalStateException("Lobby must finish preparation first");
        phase = Phase.PLAYING;
        revision++;
    }

    public void finishBattle() {
        if (phase != Phase.PLAYING)
            throw new IllegalStateException("Lobby is not playing");
        phase = Phase.FINISHED;
        revision++;
    }

    /**
     * The first player requests a rematch, the other must separately consent.
     * Do not enter PREPARING until both have explicitly accepted.
     * Called by MatchmakingService under its membership mutex.
     */
    public boolean voteRematch(long userId, boolean accepted) {
        if (phase != Phase.FINISHED || !isFull() || rematchDeclined)
            throw new IllegalStateException("Rematch is not available");
        boolean changed;
        if (hostId == userId) {
            changed = hostRematchAccepted != accepted;
            hostRematchAccepted = accepted;
        } else if (Objects.equals(guestId, userId)) {
            changed = guestRematchAccepted != accepted;
            guestRematchAccepted = accepted;
        } else throw new IllegalArgumentException("Not a room participant");
        if (changed) revision++;
        return changed;
    }

    /** A finished participant leaves the result screen; keep it for the other. */
    public void departAfterFinish(long userId) {
        if (phase != Phase.FINISHED || !contains(userId))
            throw new IllegalStateException("No finished match to leave");
        setConnected(userId, false);
        rematchDeclined = true;
        revision++;
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
