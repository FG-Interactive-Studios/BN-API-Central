package com.FGInteractive.BatalhaNaval.match.dto;

import com.FGInteractive.BatalhaNaval.match.model.Orientation;
import com.FGInteractive.BatalhaNaval.match.model.ShipType;
import java.util.List;

/** Replaces the entire private fleet atomically. Rows and columns are 0-based. */
public record PlaceFleetRequest(List<ShipPlacement> ships) {
    public record ShipPlacement(ShipType type, int row, int col, Orientation orientation) {}
}
