package com.scheduler.repository;

import com.scheduler.domain.Job;
import com.scheduler.domain.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Data access for {@link Job}.
 *
 * <p>Spring Data generates an implementation of this interface at runtime.
 * Methods whose names follow the query-derivation grammar (findByX,
 * countByY) need no body; anything more complex is written explicitly as
 * JPQL or native SQL.
 */
public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByStatus(JobStatus status);

    long countByStatus(JobStatus status);

    /**
     * Atomically claim up to {@code limit} runnable jobs for one worker.
     *
     * <p>This is the heart of the exactly-once guarantee, and it is native
     * SQL rather than JPQL because it relies on a PostgreSQL feature JPQL
     * cannot express: {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p><b>Why SKIP LOCKED.</b> Without it, ten workers polling the same
     * queue all try to lock the same oldest rows. Nine of them block, wait
     * for the first to commit, then discover the rows are no longer PENDING
     * and start again — the queue serialises and throughput collapses to
     * roughly one worker's worth no matter how many are running. SKIP
     * LOCKED tells Postgres to silently pass over rows another transaction
     * already holds, so each worker walks away with a disjoint batch and
     * the workers scale roughly linearly.
     *
     * <p><b>Why it is one statement.</b> Selecting candidates and then
     * updating them in a second statement leaves a window in which another
     * worker claims the same row. Doing the SELECT inside the UPDATE, with
     * the row locks taken by the sub-select, closes that window entirely:
     * the claim either happens atomically or does not happen.
     *
     * <p>{@code version = version + 1} is incremented by hand because a
     * native UPDATE bypasses Hibernate's optimistic-locking machinery. If it
     * were not incremented, an entity loaded before the claim would still
     * carry a matching version and could overwrite the claim.
     *
     * @return the rows claimed, now RUNNING and owned by {@code workerId}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE jobs
           SET status      = 'RUNNING',
               claimed_by  = :workerId,
               claimed_at  = now(),
               updated_at  = now(),
               version     = version + 1
         WHERE id IN (
               SELECT id
                 FROM jobs
                WHERE status = 'PENDING'
                  AND run_at <= now()
                ORDER BY run_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
         )
        RETURNING *
        """, nativeQuery = true)
    List<Job> claimBatch(@Param("workerId") String workerId, @Param("limit") int limit);

    /**
     * Return jobs stuck in RUNNING past the timeout to PENDING.
     *
     * <p>A worker that is killed mid-job leaves its rows RUNNING forever;
     * nothing else will ever claim them because the claim query only looks
     * at PENDING. This reaper is what makes the system tolerate worker
     * crashes rather than silently leaking jobs.
     *
     * <p>The timeout is a genuine trade-off: too short and a slow-but-alive
     * worker has its job stolen and run twice; too long and crash recovery
     * drags. A heartbeat column would tighten this, at the cost of a write
     * per job per interval.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE jobs
           SET status     = 'PENDING',
               claimed_by = NULL,
               claimed_at = NULL,
               updated_at = now(),
               version    = version + 1
         WHERE status = 'RUNNING'
           AND claimed_at < :cutoff
        """, nativeQuery = true)
    int reapStale(@Param("cutoff") Instant cutoff);
}
