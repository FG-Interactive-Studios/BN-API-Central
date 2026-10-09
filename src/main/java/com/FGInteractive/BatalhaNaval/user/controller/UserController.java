package com.FGInteractive.BatalhaNaval.user.controller;

import com.FGInteractive.BatalhaNaval.user.dto.*;
import com.FGInteractive.BatalhaNaval.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @GetMapping("/me")
    public PlayerProfile me(@AuthenticationPrincipal Jwt jwt) {
        return users.currentProfile(Long.parseLong(jwt.getSubject()));
    }

    @PatchMapping("/me")
    public PlayerProfile update(@AuthenticationPrincipal Jwt jwt,
                                @RequestBody UpdateProfileRequest request) {
        return users.updateProfile(Long.parseLong(jwt.getSubject()), request);
    }

    @GetMapping("/{id}")
    public PublicPlayerProfile publicProfile(@PathVariable Long id) {
        return users.publicProfile(id);
    }

}
