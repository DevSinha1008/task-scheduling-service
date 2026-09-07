package com.scheduler.service;

import com.scheduler.domain.Job;
import com.scheduler.domain.JobStatus;
import com.scheduler.dto.CreateJobRequest;
import com.scheduler.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Job lifecycle operations.
 *
 * <p>This layer exists to own <i>transaction boundaries</i>. Controllers
 * handle HTTP and repositories handle SQL; the decision about what must
 * commit or roll back together belongs in neither, and putting it here keeps
 * that decision in one reviewable place.
 */
@Service
public class JobService {

    /** Base unit for exponential backoff: 2s, then 4s, then 8s... */
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(2);
    /** Ceiling so a job with many retries does not drift hours into the future. */
    private static final Duration BACKOFF_MAX = Duration.ofMinutes(10);

    private final JobRepository jobs;

    /**
     * Constructor injection rather than @Autowired on fields. It makes the
     * dependency mandatory and visible, allows the field to be final, and
     * lets a test construct the service directly with a stub — no Spring
     * context required.
     */
    public JobService(JobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional
    public Job create(CreateJobRequest req) {
        Instant runAt = req.runAt() == null ? Instant.now() : req.runAt();
        Job job = new Job(req.name(), req.payload(), runAt, req.maxAttemptsOrDefault());
        return jobs.save(job);
    }

    /**
     * {@code readOnly = true} lets Hibernate skip dirty-checking (it will not
     * scan loaded entities for changes at flush) and lets the driver flag the
     * transaction read-only, which some setups route to a replica.
     */
    @Transactional(readOnly = true)
    public Optional<Job> findById(UUID id) {
        return jobs.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Job> findByStatus(JobStatus status) {
        return jobs.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public long countByStatus(JobStatus status) {
        return jobs.countByStatus(status);
    }

    /**
     * Claim a batch of runnable jobs for this worker.
     *
     * <p>{@code REQUIRES_NEW} forces its own transaction that commits as
     * soon as the claim is made, rather than joining a longer-running one.
     * The claim holds row locks; keeping it short is what stops other
     * workers queueing behind this one while jobs actually execute.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Job> claimBatch(String workerId, int limit) {
        return jobs.claimBatch(workerId, limit);
    }

    @Transactional
    public void markSucceeded(UUID jobId) {
        jobs.findById(jobId).ifPresent(job -> {
            job.markSucceeded();
            jobs.save(job);
        });
    }

    /**
     * Record a failure, scheduling a retry if attempts remain.
     *
     * <p>Backoff is exponential in the attempt count: waiting a fixed
     * interval hammers a struggling downstream dependency at a constant
     * rate, whereas doubling gives it room to recover. The cap keeps the
     * delay bounded.
     */
    @Transactional
    public void markFailed(UUID jobId, String error) {
        jobs.findById(jobId).ifPresent(job -> {
            Duration backoff = backoffFor(job.getAttempts());
            job.markFailed(truncate(error), Instant.now().plus(backoff));
            jobs.save(job);
        });
    }

    /** 2^attempt * base, capped. attempt 0 -> 2s, 1 -> 4s, 2 -> 8s. */
    public static Duration backoffFor(int attemptsSoFar) {
        long seconds = BACKOFF_BASE.toSeconds() * (1L << Math.min(attemptsSoFar, 20));
        Duration d = Duration.ofSeconds(seconds);
        return d.compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : d;
    }

    /** Stack traces can be enormous; the column is text but the log is not. */
    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "...[truncated]";
    }

    @Transactional
    public int reapStale(Duration timeout) {
        return jobs.reapStale(Instant.now().minus(timeout));
    }
}
