package com.FGInteractive.BatalhaNaval.user.controller;

import com.FGInteractive.BatalhaNaval.user.dto.PlayerProfile;
import com.FGInteractive.BatalhaNaval.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @GetMapping("/me")
    public PlayerProfile me(@AuthenticationPrincipal Jwt jwt) {
        return users.currentProfile(Long.valueOf(jwt.getSubject()));
    }
}
