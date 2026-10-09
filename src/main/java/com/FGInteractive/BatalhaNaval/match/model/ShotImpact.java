package com.FGInteractive.BatalhaNaval.match.model;

/** Sunk ship type becomes public only after every one of its cells is hit. */
public record ShotImpact(boolean hit, String sunkShip) {}
