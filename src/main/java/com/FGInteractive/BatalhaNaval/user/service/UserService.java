package com.FGInteractive.BatalhaNaval.user.service;

import com.FGInteractive.BatalhaNaval.auth.repository.AuthRepository;
import com.FGInteractive.BatalhaNaval.user.dto.*;
import com.FGInteractive.BatalhaNaval.user.exception.*;
import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private static final Set<String> AVATARS =
        Set.of("captain", "submarine", "destroyer", "carrier");

    private final UserRepository users;
    private final AuthRepository credentials;

    public UserService(UserRepository users, AuthRepository credentials) {
        this.users = users;
        this.credentials = credentials;
    }

    @Transactional(readOnly = true)
    public PlayerProfile currentProfile(Long userId) {
        var user = users.findById(userId).orElseThrow(PlayerNotFoundException::new);
        return privateView(user);
    }

    @Transactional(readOnly = true)
    public PublicPlayerProfile publicProfile(Long userId) {
        var user = users.findById(userId).orElseThrow(PlayerNotFoundException::new);
        return new PublicPlayerProfile(user.getId(), user.getNickname(),
            user.getAvatarId(), user.getCreatedAt());
    }

    @Transactional
    public PlayerProfile updateProfile(Long userId, UpdateProfileRequest request) {
        if (request == null || (request.nickname() == null && request.avatarId() == null)) {
            throw new InvalidProfileException("profile", "Specify nickname or avatarId");
        }

        var user = users.findById(userId).orElseThrow(PlayerNotFoundException::new);
        if (request.nickname() != null) {
            String nickname = validateNickname(request.nickname());
            if (users.existsByNicknameIgnoreCaseAndIdNot(nickname, userId)) {
                throw new NicknameTakenException();
            }
            user.setNickname(nickname);
        }
        if (request.avatarId() != null) {
            if (!AVATARS.contains(request.avatarId())) {
                throw new InvalidProfileException("avatarId", "Unknown avatarId");
            }
            user.setAvatarId(request.avatarId());
        }
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // A DB unique index also protects concurrent nickname changes.
            throw new NicknameTakenException(ex);
        }
        return privateView(user);
    }

    private PlayerProfile privateView(com.FGInteractive.BatalhaNaval.user.model.User user) {
        var auth = credentials.findByUser_Id(user.getId())
            .orElseThrow(() -> new IllegalStateException("Account credentials not found"));
        return new PlayerProfile(user.getId(), user.getNickname(),
            auth.getEmail(), user.getAvatarId(), user.getCreatedAt());
    }

    private String validateNickname(String value) {
        String candidate = value.trim();
        if (candidate.length() < 3 || candidate.length() > 30
            || candidate.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidProfileException("nickname",
                "nickname must contain 3 to 30 printable characters after trimming");
        }
        return candidate;
    }

}
