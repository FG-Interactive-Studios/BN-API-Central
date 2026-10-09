# Authentication, tokens and session management

The public endpoints are `POST /api/auth/register`, `POST /api/auth/login`,
`POST /api/auth/refresh`, `GET /api/health` and the numeric public player
profile `GET /api/users/{id}`. All other REST paths require an authenticated
Bearer access token. Access is never granted from a client-provided user ID.

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
- `PATCH /api/users/me` — update own nickname/avatar by JWT identity.
- `POST /api/auth/logout` — revoke the only active session (204).
- `POST /api/auth/change-password` — verify current password and update its BCrypt
  hash **without revoking the active session** (204). Keep using the existing
  access and refresh tokens; no additional login is needed.

A new login automatically revokes any previous session belonging to the same
player, including sessions from another device. Old JWTs and refresh tokens
are rejected immediately, and the PostgreSQL partial unique index guarantees
at most one unrevoked session per account. The former multi-session endpoints
(`GET /api/auth/sessions`, `POST /api/auth/logout-all`,
`DELETE /api/auth/sessions/{sessionId}`) were removed.

Public `GET /api/users/{id}` returns only id, nickname, avatarId and
createdAt; it does **not** reveal the email, credentials or session history.
See [profile API](player-profiles.md).

A revoked session invalidates its access JWT immediately. Unauthenticated or
invalid Bearer requests receive 401 from Spring Security. Authorization for
game data and actions must be enforced server-side on future endpoints.

## Required configuration

**Local development:** the VS Code `BN API - Local DB (Debug)` launch explicitly
uses `SPRING_PROFILES_ACTIVE=local`. When `AUTH_JWT_SECRET_B64` is blank,
the backend generates a fresh cryptographically random 48-byte HS256 key
**once per application startup**, held in memory only. JWTs issued before
a restart will no longer validate. A deliberately configured signing key
takes precedence and must pass the same Base64 and length validation.
For local terminal runs, activate the profile explicitly:
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.

**Production/default profile:** provide a persistent, secret
`AUTH_JWT_SECRET_B64` containing at least 32 random bytes encoded in Base64,
for example from `openssl rand -base64 48`. Without a valid key startup fails
closed. Never activate the `local` profile on production infrastructure. All
production API instances must use the same securely managed key until a
coordinated rotation. Do not commit the value or print it to logs.

**CI/test profiles:** continue to use their explicit test-only signing key.
They do not fall back to automatic local key generation. Use HTTPS in
production.
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
