package com.FGInteractive.BatalhaNaval.matchmaking.dto;

import jakarta.validation.constraints.NotNull;

public record SetReadyRequest(@NotNull Boolean ready) {}
