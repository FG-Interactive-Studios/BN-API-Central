package com.FGInteractive.BatalhaNaval.match.dto;
import com.FGInteractive.BatalhaNaval.match.dto.PlaceFleetRequest.ShipPlacement;
import com.FGInteractive.BatalhaNaval.match.mode.ModeSummary;
import com.FGInteractive.BatalhaNaval.match.model.ShotRecord;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.Map;

/** Per-player perspective; includes only own ship placements and observed shots. */
public record BattleResponse(
    UUID matchId,
    String lobbyCode,
    ModeSummary mode,
    String status,
    long revision,
    Long turnPlayerId,
    Long winnerId,
    List<ShipPlacement> yourShips,
    List<ShotRecord> shotsFired,
    List<ShotRecord> shotsReceived,
    String finishReason,
    Map<Long, Instant> reconnectDeadlines
) {}
