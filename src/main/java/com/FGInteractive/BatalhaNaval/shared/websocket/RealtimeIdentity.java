package com.FGInteractive.BatalhaNaval.shared.websocket;

import java.util.UUID;

/** Derived only from the validated JWT / persisted auth session, never from client JSON. */
public record RealtimeIdentity(long userId, UUID authSessionId) {}
