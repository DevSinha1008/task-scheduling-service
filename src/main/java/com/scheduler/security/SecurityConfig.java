package com.scheduler.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * The security policy, in one place.
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtFilter;

    public SecurityConfig(JwtAuthFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF protects browser sessions that authenticate via cookies,
            // which the browser attaches automatically to cross-site requests.
            // This API authenticates with an explicit Authorization header
            // that no third-party site can cause a browser to send, so the
            // attack CSRF defends against does not apply. Disabling it here
            // is correct; disabling it on a cookie-session app would not be.
            .csrf(AbstractHttpConfigurer::disable)

            // No HttpSession at all. Every request must carry its own proof
            // of identity, which is what makes the service horizontally
            // scalable without sticky sessions or a shared session store.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/actuator/health", "/health").permitAll()
                    .anyRequest().authenticated())

            // Default behaviour for an unauthenticated request is a redirect
            // to a login page, which is meaningless for a JSON API. Return a
            // bare 401 instead.
            .exceptionHandling(e -> e.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            // Run the JWT filter before the username/password filter so a
            // valid token authenticates the request before form-login logic
            // is ever consulted.
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost factor 10: ~100ms per hash. Slow on purpose — it is what makes
        // brute-forcing a leaked hash table expensive.
        return new BCryptPasswordEncoder(10);
    }
}
