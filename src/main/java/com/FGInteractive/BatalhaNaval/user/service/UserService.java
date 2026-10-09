package com.FGInteractive.BatalhaNaval.user.service;

import com.FGInteractive.BatalhaNaval.auth.repository.AuthRepository;
import com.FGInteractive.BatalhaNaval.user.dto.PlayerProfile;
import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final AuthRepository credentials;

    public UserService(UserRepository users, AuthRepository credentials) {
        this.users = users;
        this.credentials = credentials;
    }

    @Transactional(readOnly = true)
    public PlayerProfile currentProfile(Long userId) {
        var user = users.findById(userId).orElseThrow(() -> new IllegalStateException("Authenticated account not found"));
        var auth = credentials.findByUser_Id(userId).orElseThrow(() -> new IllegalStateException("Account credentials not found"));
        return new PlayerProfile(user.getId(), user.getNickname(), auth.getEmail(), user.getCreatedAt());
    }
}
