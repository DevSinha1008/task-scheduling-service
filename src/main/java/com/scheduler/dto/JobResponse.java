package com.scheduler.dto;

import com.scheduler.domain.Job;
import com.scheduler.domain.JobStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound job representation.
 *
 * <p>Separate from the entity so the API contract does not silently change
 * when the schema does, and so internal fields (the optimistic-locking
 * version, the claiming worker's identity) are not leaked to callers.
 */
public record JobResponse(
        UUID id,
        String name,
        String payload,
        JobStatus status,
        Instant runAt,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(), job.getName(), job.getPayload(), job.getStatus(),
                job.getRunAt(), job.getAttempts(), job.getMaxAttempts(),
                job.getLastError(), job.getCreatedAt(), job.getUpdatedAt());
    }
}
