package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.dto.RadarReport;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.List;

/** Planned public shots and an optional PRIVATE result; neither mutates the board. */
public record AbilityOutcome(List<Cell> shots, RadarReport privateRadarReport) {
    public AbilityOutcome { shots = List.copyOf(shots); }
}
