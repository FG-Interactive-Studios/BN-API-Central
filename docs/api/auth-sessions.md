# Authentication, tokens and session management

The public endpoints are `POST /api/auth/register`, `POST /api/auth/login`,
`POST /api/auth/refresh` and `GET /api/health`. All other REST paths require
an authenticated Bearer access token. Access is never granted from a client-provided user ID.

## Login

`POST /api/auth/login`:

```json
{"email":"captain@example.com","password":"safe-password-123"}
```

Success response (200):

```json
{
  "tokenType":"Bearer",
  "accessToken":"<signed JWT>",
  "expiresIn":900,
  "refreshToken":"<opaque secret>",
  "user":{"id":1,"nickname":"Captain","email":"captain@example.com","createdAt":"2026-10-08T23:30:00Z"}
}
```

Incorrect credentials return `401 INVALID_CREDENTIALS` without revealing
whether an email exists. The JWT is signed using HS256 and contains only the
issuer `battleship-api`, `sub` (player ID), `sid` (session ID), `nickname`,
`iat`, and `exp`. Its 15-minute lifetime is fixed. JWT nickname is display
metadata; fetch `/api/users/me` for fresh profile data. Neither a decoded JWT
nor UI state is proof of permission: the server validates signature, issuer,
expiration, session ownership and revocation on **every request**.

## Refresh and logout

`POST /api/auth/refresh`: `{"refreshToken":"<opaque secret>"}` returns a
new access token **and new refresh token**. Replace the previous refresh token
atomically in the client: it cannot be reused. The refresh token is generated
using 32 secure random bytes, is stored only as a SHA-256 hash and expires after
30 days without sliding extension. Expired sessions require logging in again.

Send `Authorization: Bearer <accessToken>` for:

- `GET /api/users/me` — own current profile; no ID in request.
- `GET /api/auth/sessions` — only active sessions of the authenticated player.
- `POST /api/auth/logout` — revoke current session (204).
- `POST /api/auth/logout-all` — revoke all own sessions (204).
- `DELETE /api/auth/sessions/{sessionId}` — revoke a session belonging to
  the authenticated player (204 or 404). Sessions of other players are never returned.

A revoked session invalidates its access JWT immediately. Unauthenticated or
invalid Bearer requests receive 401 from Spring Security. Authorization for
game data and actions must be enforced server-side on future endpoints.

## Required configuration

Set `AUTH_JWT_SECRET_B64` to a secret generated from at least **32 random bytes**
of Base64, e.g. `openssl rand -base64 48`, for **every runtime environment**.
No development signing key is baked into production configuration. Missing,
malformed, or short keys prevent startup. The fixed signing key in test/CI
profiles is **only** for automated tests and must never be used in production.

The VS Code local debug launch reads the untracked `.env` file; copy
`.env.example` and fill the signing key before running. For terminal usage,
export the environment variable in the shell. Use HTTPS in production.
Do not log tokens, commit secrets, or put refresh tokens into localStorage.
Prefer memory-backed client session storage; avoid leaking tokens to telemetry,
third-party scripts, or URLs.

The existing WebSocket implementation has no gameplay handlers yet. Future
WebSocket authentication must validate the Bearer token at handshake and
enforce player/session ownership for every game event.

## Operational note

The current login implementation uses generic errors but does **not** yet
apply login-attempt rate limiting. Add throttling at the edge/API before
deploying a public login surface, together with monitoring for password
spraying and credential stuffing. Avoid sending refresh tokens to analytics.
