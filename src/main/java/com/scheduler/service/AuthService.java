package com.scheduler.service;

import com.scheduler.domain.AppUser;
import com.scheduler.dto.AuthRequest;
import com.scheduler.repository.AppUserRepository;
import com.scheduler.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(AppUserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /** @return the issued token, or empty if the username is taken. */
    @Transactional
    public Optional<String> register(AuthRequest req) {
        if (users.existsByUsername(req.username())) {
            return Optional.empty();
        }
        users.save(new AppUser(req.username(), encoder.encode(req.password())));
        return Optional.of(jwt.issue(req.username()));
    }

    /**
     * @return the issued token, or empty if the credentials are wrong.
     *
     * <p>Unknown user and wrong password deliberately produce the same
     * result. Distinguishing them lets an attacker enumerate valid usernames
     * by watching which errors come back.
     */
    @Transactional(readOnly = true)
    public Optional<String> login(AuthRequest req) {
        return users.findByUsername(req.username())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .map(u -> jwt.issue(u.getUsername()));
    }
}
