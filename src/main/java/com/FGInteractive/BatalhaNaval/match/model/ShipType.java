package com.FGInteractive.BatalhaNaval.match.model;

/** One of each ship type is required; the two three-cell vessels are distinct. */
public enum ShipType {
    CARRIER(5), BATTLESHIP(4), CRUISER(3), SUBMARINE(3), DESTROYER(2);

    private final int length;
    ShipType(int length) { this.length = length; }
    public int length() { return length; }
}
