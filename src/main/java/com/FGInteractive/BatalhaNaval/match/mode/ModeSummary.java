package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.List;
import java.util.Map;
/** Safe public metadata, no server rule objects or private placements. */
public record ModeSummary(String id, String name, GeometryInfo geometry,
    List<ShipDefinition> fleet, String placement, Map<String,String> mechanics) {
    public record GeometryInfo(String kind, int rows, int columns, List<Cell> cells) {}
}
