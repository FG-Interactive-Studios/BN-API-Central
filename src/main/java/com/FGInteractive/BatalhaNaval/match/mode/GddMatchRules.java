package com.FGInteractive.BatalhaNaval.match.mode;

import java.time.Duration;

/** Server-authoritative timings adopted from Game Design Document v1.2.
 * Experimental geometries remain registered but disabled until GD approval.
 */
public final class GddMatchRules {
    public static final Duration PREPARATION = Duration.ofSeconds(30);
    public static final Duration TURN = Duration.ofSeconds(20);
    public static final Duration MATCH = Duration.ofMinutes(10);
    public static final Duration RECONNECT = Duration.ofSeconds(15);
    public static final int POWER_POINTS_PER_BOARD = 4;
    public static final int MAX_CONSECUTIVE_TURN_TIMEOUTS = 3;

    private GddMatchRules() {}

    public static boolean appliesTo(GameModeDefinition mode) {
        return mode.id().equals("classic") || mode.id().equals("classic-powers");
    }
}
