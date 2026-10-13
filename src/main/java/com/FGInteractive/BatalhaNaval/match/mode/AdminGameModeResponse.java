package com.FGInteractive.BatalhaNaval.match.mode;

import java.time.Instant;

/** Describes a server-registered mode and its administrator-managed availability. */
public record AdminGameModeResponse(
    ModeSummary mode, boolean enabled, Instant updatedAt, Long updatedBy
) {}
