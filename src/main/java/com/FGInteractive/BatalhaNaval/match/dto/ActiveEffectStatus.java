package com.FGInteractive.BatalhaNaval.match.dto;

/** Only the current player's status is serialized; never discloses enemy private data. */
public record ActiveEffectStatus(String id, String label, int actionsRemaining) {}
