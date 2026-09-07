package com.scheduler.worker;

import com.scheduler.domain.Job;
import com.scheduler.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Polls the queue, claims a batch, and runs the jobs on a thread pool.
 *
 * <p>Why polling rather than a push queue: the job store is Postgres, which
 * is the right choice here because jobs must survive a restart and be
 * inspectable, and Postgres has no built-in push. LISTEN/NOTIFY would cut
 * idle latency but does not survive a disconnect, so a poll loop is still
 * needed as the backstop — the poll loop alone is simpler and correct.
 *
 * <p>The interval is a straightforward trade: shorter means lower latency
 * from "runnable" to "running", and more empty queries against the database.
 *
 * <p>{@code @ConditionalOnProperty} lets tests disable the worker so a test
 * can inspect a PENDING job without it being claimed mid-assertion.
 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobService jobs;
    private final ThreadPoolExecutor pool;
    private final JobHandler handler;
    private final int batchSize;
    private final Duration staleTimeout;

    /**
     * Identifies this process in the {@code claimed_by} column. A random
     * suffix keeps two instances on the same host distinguishable, which
     * matters when reading the table to work out which worker died.
     */
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    // Counters for the /stats view and for benchmarking. AtomicLong because
    // pool threads increment these concurrently; a plain long would lose
    // updates under a read-modify-write race.
    private final AtomicLong executed = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public JobWorker(JobService jobs,
                     ThreadPoolExecutor pool,
                     JobHandler handler,
                     @Value("${app.worker.batch-size:20}") int batchSize,
                     @Value("${app.worker.stale-timeout-seconds:300}") long staleTimeoutSeconds) {
        this.jobs = jobs;
        this.pool = pool;
        this.handler = handler;
        this.batchSize = batchSize;
        this.staleTimeout = Duration.ofSeconds(staleTimeoutSeconds);
    }

    /**
     * {@code fixedDelay} rather than {@code fixedRate}: the delay is measured
     * from the END of the previous run, so a slow poll cannot cause runs to
     * overlap or pile up. With fixedRate, a poll that takes longer than the
     * interval would have the next one queued immediately behind it.
     */
    @Scheduled(fixedDelayString = "${app.worker.poll-interval-ms:500}")
    public void pollAndExecute() {
        try {
            List<Job> claimed = jobs.claimBatch(workerId, batchSize);
            if (claimed.isEmpty()) {
                return;
            }
            log.debug("{} claimed {} jobs", workerId, claimed.size());

            // The claim transaction has already committed by this point, so
            // execution happens outside it. Holding a database transaction
            // open for the duration of the work would pin a connection per
            // in-flight job and exhaust the pool.
            for (Job job : claimed) {
                pool.submit(() -> execute(job));
            }
        } catch (Exception e) {
            // A poll failure (database blip) must not kill the scheduler
            // thread; an uncaught exception here would stop all future polls.
            log.error("poll failed", e);
        }
    }

    private void execute(Job job) {
        executed.incrementAndGet();
        try {
            handler.handle(job);
            jobs.markSucceeded(job.getId());
            succeeded.incrementAndGet();
        } catch (Exception e) {
            failed.incrementAndGet();
            log.warn("job {} failed on attempt {}: {}",
                    job.getId(), job.getAttempts() + 1, e.toString());
            jobs.markFailed(job.getId(), e.toString());
        }
    }

    /**
     * Return jobs orphaned by a crashed worker to PENDING.
     *
     * <p>Runs an order of magnitude less often than the poll: it is a
     * recovery path, not a hot path, and it writes to rows other workers may
     * be reading.
     */
    @Scheduled(fixedDelayString = "${app.worker.reaper-interval-ms:30000}")
    public void reapStaleJobs() {
        try {
            int reaped = jobs.reapStale(staleTimeout);
            if (reaped > 0) {
                log.info("reaped {} stale jobs back to PENDING", reaped);
            }
        } catch (Exception e) {
            log.error("reaper failed", e);
        }
    }

    /** Test/benchmark helper: block until the pool has drained. */
    public void awaitQuiescence(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                Thread.sleep(50);  // settle: let the final commits land
                if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) return;
            }
            Thread.sleep(25);
        }
    }

    public String getWorkerId() { return workerId; }
    public long getExecutedCount() { return executed.get(); }
    public long getSucceededCount() { return succeeded.get(); }
    public long getFailedCount() { return failed.get(); }
}
