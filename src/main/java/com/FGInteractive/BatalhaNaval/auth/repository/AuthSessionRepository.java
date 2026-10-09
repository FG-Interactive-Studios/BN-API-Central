package com.FGInteractive.BatalhaNaval.auth.repository;

import com.FGInteractive.BatalhaNaval.auth.model.AuthSession;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s join fetch s.user where s.refreshTokenHash = :hash")
    Optional<AuthSession> lockByRefreshTokenHash(@Param("hash") String hash);

    boolean existsByIdAndUser_IdAndRevokedAtIsNullAndExpiresAtAfter(
        UUID id, Long userId, Instant now);

    Optional<AuthSession> findByIdAndUser_Id(UUID id, Long userId);

    @Modifying(flushAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now where s.user.id = :userId and s.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
