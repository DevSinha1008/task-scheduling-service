package com.scheduler.controller;

import com.scheduler.dto.AuthRequest;
import com.scheduler.dto.AuthResponse;
import com.scheduler.security.JwtService;
import com.scheduler.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final JwtService jwt;

    public AuthController(AuthService auth, JwtService jwt) {
        this.auth = auth;
        this.jwt = jwt;
    }

    /** 201 with a token, or 409 if the username already exists. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody AuthRequest req) {
        return auth.register(req)
                .map(token -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(AuthResponse.bearer(token, jwt.getExpirySeconds())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).build());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest req) {
        return auth.login(req)
                .map(token -> ResponseEntity.ok(AuthResponse.bearer(token, jwt.getExpirySeconds())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }
}
