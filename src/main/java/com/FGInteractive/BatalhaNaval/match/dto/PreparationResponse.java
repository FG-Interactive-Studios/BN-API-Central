package com.FGInteractive.BatalhaNaval.match.dto;

import java.util.List;
import java.util.UUID;

public record PreparationResponse(
    String lobbyCode,
    UUID preparationId,
    int boardSize,
    long revision,
    boolean fleetPlaced,
    boolean fleetConfirmed,
    boolean opponentPlaced,
    boolean opponentConfirmed,
    boolean bothConfirmed,
    List<PlaceFleetRequest.ShipPlacement> yourShips
) {}
