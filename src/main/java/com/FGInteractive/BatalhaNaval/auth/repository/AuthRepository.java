package com.FGInteractive.BatalhaNaval.auth.repository;

import com.FGInteractive.BatalhaNaval.auth.model.Auth;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthRepository extends JpaRepository<Auth, Long> {
    boolean existsByEmail(String email);
    Optional<Auth> findByEmail(String email);
    Optional<Auth> findByUser_Id(Long userId);
}
