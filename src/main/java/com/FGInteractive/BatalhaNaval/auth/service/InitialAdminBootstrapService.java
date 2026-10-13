package com.FGInteractive.BatalhaNaval.auth.service;

import com.FGInteractive.BatalhaNaval.auth.model.Auth;
import com.FGInteractive.BatalhaNaval.auth.repository.AuthRepository;
import com.FGInteractive.BatalhaNaval.user.model.User;
import com.FGInteractive.BatalhaNaval.user.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-admin provisioning. This is deliberately not a REST endpoint.
 * The only plaintext copy of the generated password is written to a local
 * owner-restricted file once. Never put it in logs, JWT claims or the DB.
 */
@Service
public class InitialAdminBootstrapService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserRepository users;
    private final AuthRepository credentials;
    private final PasswordEncoder encoder;

    public InitialAdminBootstrapService(UserRepository users, AuthRepository credentials,
                                        PasswordEncoder encoder) {
        this.users = users;
        this.credentials = credentials;
        this.encoder = encoder;
    }

    @Transactional
    public boolean createIfMissing(Path output) {
        // If at least one admin exists, leave all users and files alone.
        if (users.existsByAdminTrue()) return false;
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            throw new IllegalStateException("Initial admin credentials file already exists: "
                + target + ". An admin is missing; move the old file out of the way "
                + "only after verifying the database state, then restart.");
        }

        byte[] nameBytes = new byte[8], secretBytes = new byte[32];
        RANDOM.nextBytes(nameBytes);
        RANDOM.nextBytes(secretBytes);
        String suffix = HexFormat.of().formatHex(nameBytes);
        String nickname = "bn-admin-" + suffix;
        String email = nickname + "@bootstrap.battleship.invalid";
        String password = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(secretBytes);

        boolean fileCreated = false;
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                // Default folder is dedicated to this application; preserve
                // any existing user-provided folder's own permissions.
                if (parent.getFileName().toString().equals(".battleship")
                    && Files.getFileStore(parent).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(parent,
                        PosixFilePermissions.fromString("rwx------"));
                }
            }

            // CREATE_NEW prevents overwriting an admin secret after a DB reset.
            if (Files.getFileStore(parent != null ? parent : target.toAbsolutePath().getParent())
                .supportsFileAttributeView("posix")) {
                Files.createFile(target,
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(target);
                // On Windows, the file lives under the current account's
                // private user profile unless explicitly configured otherwise.
            }
            fileCreated = true;
            Files.writeString(target, "email=" + email + "\npassword=" + password + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            Instant now = Instant.now();
            User admin = new User(nickname, now);
            admin.markInitialAdministrator();
            admin = users.saveAndFlush(admin);
            credentials.saveAndFlush(new Auth(admin, email, encoder.encode(password), now));
            return true;
        } catch (Exception ex) {
            if (fileCreated) {
                try { Files.deleteIfExists(target); }
                catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            }
            throw new IllegalStateException("Initial administrator provisioning failed; "
                + "no credentials were logged. Verify filesystem permissions and DB state.", ex);
        }
    }
}
