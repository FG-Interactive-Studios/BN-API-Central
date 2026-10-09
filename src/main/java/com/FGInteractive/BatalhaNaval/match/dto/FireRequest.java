package com.FGInteractive.BatalhaNaval.match.dto;

/** Client submits only the destination; identity, match and attacker come from JWT. */
public record FireRequest(Integer row, Integer col) {}
