package com.FGInteractive.BatalhaNaval.auth.dto;


import java.time.Instant;
import java.util.Map;
public record AuthErrorResponse(Instant timestamp, int status, String code, String message, Map<String,String> fieldErrors) {}
