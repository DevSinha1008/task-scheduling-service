package com.scheduler.repository;

import com.scheduler.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Spring Data derives the query from the method name: "findBy" + the
     * field "username" becomes SELECT ... WHERE username = ?. Returning
     * Optional rather than null makes the "no such user" case impossible
     * to forget at the call site.
     */
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
