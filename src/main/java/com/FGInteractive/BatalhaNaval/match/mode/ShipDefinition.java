package com.FGInteractive.BatalhaNaval.match.mode;

/** Identity and length are mode data, not a hard-coded ShipType enum. */
public record ShipDefinition(String id, int length) {
    public ShipDefinition {
        if (id == null || !id.matches("[A-Z][A-Z0-9_]{1,39}") || length < 1 || length > 30)
            throw new IllegalArgumentException("Invalid ship definition");
    }
}
