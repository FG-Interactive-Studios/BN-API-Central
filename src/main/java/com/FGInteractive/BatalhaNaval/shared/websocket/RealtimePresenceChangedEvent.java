package com.FGInteractive.BatalhaNaval.shared.websocket;

/** Fired by the socket transport; business domains may react without coupling transports to services. */
public record RealtimePresenceChangedEvent(long userId) {}
