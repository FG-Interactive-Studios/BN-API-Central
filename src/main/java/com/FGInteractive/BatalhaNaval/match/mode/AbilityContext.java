package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.Set;

/** Immutable input to deterministic ability planning. Never exposed to the browser. */
public record AbilityContext(GameModeDefinition mode, Board ownBoard, Board enemyBoard,
                             Set<Cell> ownShots, Set<Cell> enemyShots) {}
