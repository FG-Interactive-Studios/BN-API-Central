package com.FGInteractive.BatalhaNaval.match.mode;

/** Only availability can be edited; clients cannot submit arbitrary game rules. */
public record SetGameModeAvailabilityRequest(Boolean enabled) {}
