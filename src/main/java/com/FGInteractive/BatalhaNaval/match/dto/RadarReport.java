package com.FGInteractive.BatalhaNaval.match.dto;

/** Private detection aggregate: never disclose individual enemy ship positions. */
public record RadarReport(int row, int col, int contacts) {}
