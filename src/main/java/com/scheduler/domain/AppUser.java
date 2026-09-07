package com.scheduler.domain;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * An API user. Named AppUser rather than User because "user" is a reserved
 * word in PostgreSQL and an unquoted table called {@code user} fails to
 * create — the table is explicitly named {@code app_users} for the same
 * reason.
 *
 * <p>Only the BCrypt hash of the password is stored. BCrypt is deliberately
 * slow and salts each hash individually, so identical passwords produce
 * different hashes and offline brute-forcing is expensive.
 */
@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    protected AppUser() { }

    public AppUser(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
}
