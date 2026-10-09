package com.FGInteractive.BatalhaNaval.shared.security;

import com.FGInteractive.BatalhaNaval.auth.repository.AuthSessionRepository;
import java.time.Instant;
import java.security.SecureRandom;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class JwtSecurityConfig {
    private static final Logger logger = LoggerFactory.getLogger(JwtSecurityConfig.class);
    private static final SecureRandom secureRandom = new SecureRandom();
    @Bean
    SecretKey jwtSigningKey(@Value("${auth.jwt.secret-base64:}") String base64, Environment environment) {
        if (base64 == null || base64.isBlank()) {
            if (!environment.acceptsProfiles(Profiles.of("local"))) {
                throw new IllegalStateException(
                    "AUTH_JWT_SECRET_B64 is required outside the local development profile");
            }
            byte[] ephemeralKey = new byte[48];
            secureRandom.nextBytes(ephemeralKey);
            logger.warn("Generated ephemeral JWT signing key for local development; "
                + "access tokens issued before application restart will become invalid");
            return new SecretKeySpec(ephemeralKey, "HmacSHA256");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("AUTH_JWT_SECRET_B64 must be valid Base64", ex);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException(
                "AUTH_JWT_SECRET_B64 must decode to at least 32 random bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AuthSessionRepository sessions) {
        NimbusJwtDecoder delegate = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
            .macAlgorithm(MacAlgorithm.HS256).build();
        delegate.setJwtValidator(JwtValidators.createDefaultWithIssuer("battleship-api"));

        return token -> {
            Jwt jwt = delegate.decode(token);
            try {
                Long userId = Long.valueOf(jwt.getSubject());
                UUID sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
                if (!sessions.existsByIdAndUser_IdAndRevokedAtIsNullAndExpiresAtAfter(
                    sessionId, userId, Instant.now())) {
                    throw new BadJwtException("Session revoked or expired");
                }
            } catch (IllegalArgumentException | NullPointerException ex) {
                throw new BadJwtException("Invalid token identity", ex);
            }
            return jwt;
        };
    }
}
