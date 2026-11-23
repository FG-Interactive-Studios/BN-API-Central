package com.FGInteractive.BatalhaNaval.matchmaking.dto;

import jakarta.validation.constraints.NotNull;

/** Explicit consent or withdrawal; the caller is derived exclusively from JWT. */
public record SetRematchRequest(@NotNull Boolean accepted) {}
