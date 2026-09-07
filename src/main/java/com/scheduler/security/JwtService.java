package com.scheduler.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Issues and verifies JSON Web Tokens.
 *
 * <p>A JWT is three base64 segments: header, claims, signature. The claims
 * are only ENCODED, not encrypted — anyone holding the token can read them,
 * so nothing secret goes in. What the signature buys is integrity: it is an
 * HMAC over the first two segments using a server-side key, so a caller can
 * neither forge a token nor edit the claims of one they hold without
 * invalidating the signature.
 *
 * <p>The payoff is statelessness. The server stores no sessions; it verifies
 * the signature and trusts the claims. That scales horizontally with no
 * shared session store, at the cost of not being able to revoke a token
 * before it expires — hence short lifetimes.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiry-seconds:3600}") long expirySeconds) {
        // HS256 requires >= 256 bits of key material; a short secret throws
        // here at startup rather than failing mysteriously at first login.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = expirySeconds;
    }

    public String issue(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * Verify signature and expiry, returning the subject if valid.
     *
     * <p>Returns Optional.empty() rather than throwing: an invalid token is
     * an expected, routine event on a public endpoint (expired, malformed,
     * tampered), not an exceptional condition. The filter treats absence as
     * "unauthenticated" and moves on.
     */
    public Optional<String> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long getExpirySeconds() { return expirySeconds; }
}
