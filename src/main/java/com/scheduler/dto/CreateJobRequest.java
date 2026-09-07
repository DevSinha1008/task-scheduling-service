package com.scheduler.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * Incoming job-creation payload.
 *
 * <p>A record, not the Job entity. Binding requests straight onto entities
 * lets a caller set fields they have no business setting (status, attempts,
 * version) — "mass assignment". A separate DTO makes the writable surface
 * explicit and lets validation live on the boundary where bad input arrives.
 *
 * <p>The annotations are Bean Validation; @Valid on the controller parameter
 * triggers them and a violation becomes a 400 before any code runs.
 */
public record CreateJobRequest(
        @NotBlank(message = "name is required")
        String name,

        String payload,

        /** Optional; null means "run as soon as possible". */
        Instant runAt,

        @Min(value = 1, message = "maxAttempts must be at least 1")
        @Max(value = 10, message = "maxAttempts must not exceed 10")
        Integer maxAttempts
) {
    public int maxAttemptsOrDefault() {
        return maxAttempts == null ? 3 : maxAttempts;
    }
}
