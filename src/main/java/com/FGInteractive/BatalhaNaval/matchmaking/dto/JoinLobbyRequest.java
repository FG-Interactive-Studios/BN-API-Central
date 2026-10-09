package com.FGInteractive.BatalhaNaval.matchmaking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinLobbyRequest(
    @NotBlank @Size(max = 16) String code
) {}
