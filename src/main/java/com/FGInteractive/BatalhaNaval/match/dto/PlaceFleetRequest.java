package com.FGInteractive.BatalhaNaval.match.dto;

import com.FGInteractive.BatalhaNaval.match.model.Orientation;
import java.util.List;

/** Ship ids are defined by the selected game mode, not by an enum. */
public record PlaceFleetRequest(List<ShipPlacement> ships) {
    public record ShipPlacement(String type, Integer row, Integer col, Orientation orientation) {}
}
