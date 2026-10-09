package com.FGInteractive.BatalhaNaval.match.mode;
import com.FGInteractive.BatalhaNaval.match.model.Board;
import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.Set;

public final class AllShipsSunkRule implements VictoryRule {
    @Override public String id() { return "all-ships-sunk"; }
    @Override public boolean defeated(Board defender, Set<Cell> incomingShots) {
        return incomingShots.containsAll(defender.occupied());
    }
}
