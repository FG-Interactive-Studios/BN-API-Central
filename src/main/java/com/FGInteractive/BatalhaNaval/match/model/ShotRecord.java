package com.FGInteractive.BatalhaNaval.match.model;

/**
 * Successful attacks are permanent. A shielded/deflected attempt is marked blocked
 * and can be retried later; it does not count as a hit for victory calculations.
 */
public record ShotRecord(int row, int col, boolean hit, String sunkShip, boolean blocked) {
    public ShotRecord(int row, int col, boolean hit, String sunkShip) {
        this(row,col,hit,sunkShip,false);
    }
}
