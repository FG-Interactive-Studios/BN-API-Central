package com.FGInteractive.BatalhaNaval.auth.repository;


import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.FGInteractive.BatalhaNaval.auth.model.Auth;
public interface AuthRepository extends JpaRepository<Auth,Long> {
    boolean existsByEmail(String email);
    Optional<Auth> findByEmail(String email);
}
