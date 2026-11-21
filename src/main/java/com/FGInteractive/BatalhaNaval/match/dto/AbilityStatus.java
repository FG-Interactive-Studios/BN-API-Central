package com.FGInteractive.BatalhaNaval.match.dto;

/** Public ability definition + private per-player availability. */
public record AbilityStatus(String id, String name, int usesRemaining,
                            int targetCount, String targetScope, String description,
                            boolean locked) {}
