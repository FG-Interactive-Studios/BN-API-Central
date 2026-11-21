package com.FGInteractive.BatalhaNaval.match.dto;

/** Available only in the authenticated player's own battle snapshot. */
public record AbilityStatus(String id, String name, int usesRemaining) {}
