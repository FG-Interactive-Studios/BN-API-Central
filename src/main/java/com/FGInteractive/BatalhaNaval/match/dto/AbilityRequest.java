package com.FGInteractive.BatalhaNaval.match.dto;

import com.FGInteractive.BatalhaNaval.match.model.Cell;
import java.util.List;

/** Player identity and effects are NEVER provided by the browser. */
public record AbilityRequest(String abilityId, List<Cell> targets) {}
