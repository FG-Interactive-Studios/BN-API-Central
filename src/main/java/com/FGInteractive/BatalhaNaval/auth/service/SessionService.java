package com.FGInteractive.BatalhaNaval.auth.service;

import com.FGInteractive.BatalhaNaval.auth.dto.*;
import com.FGInteractive.BatalhaNaval.auth.exception.*;
import com.FGInteractive.BatalhaNaval.auth.model.*;
import com.FGInteractive.BatalhaNaval.auth.repository.*;
import com.FGInteractive.BatalhaNaval.user.dto.PlayerProfile;
import com.FGInteractive.BatalhaNaval.user.model.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Base64;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    private static final Duration SESSION_LIFETIME = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthRepository authRepository;
    private final AuthSessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    public SessionService(AuthRepository authRepository, AuthSessionRepository sessionRepository,
                          PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder) {
        this.authRepository = authRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        Auth credential = authRepository.findByEmail(email).orElseThrow(InvalidSessionException::new);
        if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
            throw new InvalidSessionException();
        }

        Instant now = Instant.now();
        String refreshToken = newRefreshToken();
        AuthSession session = sessionRepository.save(new AuthSession(
            credential.getUser(), hash(refreshToken), now, now.plus(SESSION_LIFETIME)));
        return tokens(session, credential.getEmail(), refreshToken, now);
    }

    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        String supplied = request.refreshToken();
        if (supplied == null || supplied.length() > 256 || !supplied.matches("[A-Za-z0-9_-]{43}")) {
            throw new InvalidSessionException();
        }
        AuthSession session = sessionRepository.lockByRefreshTokenHash(hash(supplied))
            .orElseThrow(InvalidSessionException::new);

        Instant now = Instant.now();
        if (!session.isActive(now)) {
            throw new InvalidSessionException();
        }
        String nextRefreshToken = newRefreshToken();
        session.rotateRefreshToken(hash(nextRefreshToken));
        String email = authRepository.findByUser_Id(session.getUser().getId())
            .orElseThrow(InvalidSessionException::new).getEmail();
        return tokens(session, email, nextRefreshToken, now);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> activeSessions(long userId, UUID currentId) {
        return sessionRepository.findByUser_IdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            userId, Instant.now()).stream().map(s ->
            new SessionResponse(s.getId(), s.getCreatedAt(), s.getExpiresAt(), s.getId().equals(currentId))
        ).toList();
    }

    @Transactional
    public void logout(long userId, UUID sessionId) {
        sessionRepository.findByIdAndUser_Id(sessionId, userId)
            .ifPresent(session -> session.revoke(Instant.now()));
    }

    @Transactional
    public void revoke(long userId, UUID sessionId) {
        var session = sessionRepository.findByIdAndUser_Id(sessionId, userId)
            .orElseThrow(SessionNotFoundException::new);
        session.revoke(Instant.now());
    }

    @Transactional
    public void logoutAll(long userId) {
        sessionRepository.revokeAllForUser(userId, Instant.now());
    }

    private TokenResponse tokens(AuthSession session, String email, String refreshToken, Instant now) {
        User user = session.getUser();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("battleship-api")
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(ACCESS_LIFETIME))
            .claim("sid", session.getId().toString())
            .claim("nickname", user.getNickname())
            .build();
        String jwt = jwtEncoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        return new TokenResponse("Bearer", jwt, ACCESS_LIFETIME.toSeconds(), refreshToken,
            new PlayerProfile(user.getId(), user.getNickname(), email, user.getCreatedAt()));
    }

    private static String newRefreshToken() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
