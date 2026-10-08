package com.FGInteractive.BatalhaNaval.user.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import com.FGInteractive.BatalhaNaval.user.model.User;
public interface UserRepository extends JpaRepository<User,Long> {
    boolean existsByNicknameIgnoreCase(String nickname);
}
