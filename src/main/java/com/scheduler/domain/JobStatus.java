package com.scheduler.domain;

/**
 * Job lifecycle states.
 *
 * <p>Stored as a STRING column rather than an ordinal. Ordinals are compact
 * but positionally fragile: inserting a new constant in the middle silently
 * reassigns the meaning of every existing row. Readable values in the
 * database are also far easier to debug against.
 */
public enum JobStatus {
    /** Eligible to be claimed once {@code runAt} has passed. */
    PENDING,
    /** Claimed by a worker and currently executing. */
    RUNNING,
    /** Completed successfully; terminal. */
    SUCCEEDED,
    /** Retries exhausted; terminal, requires manual intervention. */
    DEAD
}
