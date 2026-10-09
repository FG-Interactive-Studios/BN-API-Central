package com.FGInteractive.BatalhaNaval.shared.websocket;

import java.time.Instant;

public record RealtimeTicketResponse(String ticket, Instant expiresAt) {}
