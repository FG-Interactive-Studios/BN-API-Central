package com.FGInteractive.BatalhaNaval.match.mode;

import com.FGInteractive.BatalhaNaval.match.dto.RadarReport;
import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.List;

/** The battle engine applies these typed actions; no power-id switches in the engine. */
public sealed interface AbilityEffect permits AbilityEffect.Attack, AbilityEffect.Scan,
        AbilityEffect.Shield, AbilityEffect.LastChance, AbilityEffect.Jam, AbilityEffect.Move {
    record Attack(List<Cell> cells) implements AbilityEffect {
        public Attack { cells = List.copyOf(cells); }
    }
    record Scan(RadarReport report) implements AbilityEffect {}
    record Shield(String shipType) implements AbilityEffect {}
    record LastChance() implements AbilityEffect {}
    record Jam() implements AbilityEffect {}
    record Move(Board newBoard) implements AbilityEffect {}
}
