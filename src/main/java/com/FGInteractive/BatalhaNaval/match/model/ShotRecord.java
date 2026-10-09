package com.FGInteractive.BatalhaNaval.match.model;

/** A publicly known shot; never serializes hidden opponent ship locations. */
public record ShotRecord(int row, int col, boolean hit, String sunkShip) {}
