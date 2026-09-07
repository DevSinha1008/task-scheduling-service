package com.scheduler.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A unit of scheduled work.
 *
 * <p>Lifecycle: PENDING -> RUNNING -> SUCCEEDED
 *                              \-> FAILED (retries remain) -> PENDING
 *                              \-> DEAD    (retries exhausted)
 *
 * <p><b>Exactly-once execution.</b> Two mechanisms combine to give it:
 *
 * <ol>
 *   <li><b>Claim-by-update.</b> A worker does not "read then run"; it
 *       atomically flips PENDING -> RUNNING in a single UPDATE guarded by
 *       {@code WHERE status = 'PENDING'}. The database serialises those
 *       updates, so exactly one worker's UPDATE affects the row and every
 *       other worker's affects zero rows and is told to move on.</li>
 *   <li><b>Optimistic locking</b> via {@link #version}. Hibernate appends
 *       {@code AND version = ?} to every UPDATE and increments the column.
 *       If another transaction modified the row in between, zero rows match
 *       and Hibernate raises {@link jakarta.persistence.OptimisticLockException}
 *       rather than silently overwriting the other transaction's work.</li>
 * </ol>
 *
 * <p>The honest boundary: this is exactly-once <i>claiming</i>. If a worker
 * crashes after the side effect but before committing SUCCEEDED, the job is
 * reaped back to PENDING and runs again — at-least-once in that window. True
 * end-to-end exactly-once would require the side effect and the state change
 * to share one transaction, which is impossible when the side effect is an
 * external call. Idempotent handlers are the standard answer.
 */
@Entity
@Table(
    name = "jobs",
    indexes = {
        // The worker's claim query filters on (status, run_at) and orders by
        // run_at. This composite index lets Postgres find the next runnable
        // job without scanning the whole table as it grows.
        @Index(name = "idx_jobs_status_run_at", columnList = "status, run_at")
    }
)
public class Job {

    @Id
    @GeneratedValue
    private UUID id;

    /** Caller-supplied name, e.g. "send-welcome-email". */
    @Column(nullable = false, length = 200)
    private String name;

    /** Opaque JSON payload handed to the handler. Deliberately untyped. */
    @Column(columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status = JobStatus.PENDING;

    /** Earliest instant this job may run. Backoff pushes this forward. */
    @Column(name = "run_at", nullable = false)
    private Instant runAt;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    /** Identifies the worker holding the claim; used by the stale-job reaper. */
    @Column(name = "claimed_by", length = 100)
    private String claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic-locking discriminator, managed entirely by Hibernate.
     * Never set this by hand — Hibernate reads it into the WHERE clause and
     * increments it on every UPDATE.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected Job() {
        // Required by JPA. Hibernate instantiates entities reflectively via
        // the no-arg constructor before populating fields.
    }

    public Job(String name, String payload, Instant runAt, int maxAttempts) {
        this.name = name;
        this.payload = payload;
        this.runAt = runAt;
        this.maxAttempts = maxAttempts;
    }

    /** Timestamps set once, on first persist. */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.runAt == null) {
            this.runAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // --- State transitions, kept on the entity so invariants live with the data ---

    /** Mark a claimed job as finished successfully. */
    public void markSucceeded() {
        this.status = JobStatus.SUCCEEDED;
        this.claimedBy = null;
        this.claimedAt = null;
        this.lastError = null;
    }

    /**
     * Record a failed attempt. If retries remain the job returns to PENDING
     * with {@code runAt} pushed into the future by the supplied backoff;
     * otherwise it becomes DEAD and is never retried.
     */
    public void markFailed(String error, Instant nextRunAt) {
        this.attempts += 1;
        this.lastError = error;
        this.claimedBy = null;
        this.claimedAt = null;
        if (this.attempts >= this.maxAttempts) {
            this.status = JobStatus.DEAD;
        } else {
            this.status = JobStatus.PENDING;
            this.runAt = nextRunAt;
        }
    }

    // --- Accessors ---

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getPayload() { return payload; }
    public JobStatus getStatus() { return status; }
    public Instant getRunAt() { return runAt; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getClaimedBy() { return claimedBy; }
    public Instant getClaimedAt() { return claimedAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void setStatus(JobStatus status) { this.status = status; }
    public void setRunAt(Instant runAt) { this.runAt = runAt; }
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
}
