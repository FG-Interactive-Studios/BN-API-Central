package com.FGInteractive.BatalhaNaval.auth.repository;

import com.FGInteractive.BatalhaNaval.auth.model.Auth;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthRepository extends JpaRepository<Auth, Long> {
    boolean existsByEmail(String email);
    Optional<Auth> findByEmail(String email);
    Optional<Auth> findByUser_Id(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Auth a where a.id = :id")
    Optional<Auth> lockById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Auth a where a.user.id = :userId")
    Optional<Auth> lockByUserId(@Param("userId") Long userId);
}
