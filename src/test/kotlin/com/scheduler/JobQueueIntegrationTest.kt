package com.scheduler

import com.scheduler.domain.Job
import com.scheduler.domain.JobStatus
import com.scheduler.repository.JobRepository
import com.scheduler.service.JobService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests against a real PostgreSQL in Docker.
 *
 * The central test here is [concurrent workers never claim the same job],
 * and it only means anything against real Postgres: it exercises FOR UPDATE
 * SKIP LOCKED and row-level locking under genuine concurrent transactions.
 * On H2 the same test would pass while proving nothing about production.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobQueueIntegrationTest {

    @Autowired lateinit var jobs: JobService
    @Autowired lateinit var repo: JobRepository

    @BeforeEach
    fun clean() = repo.deleteAll()

    private fun enqueue(count: Int, name: String = "test-job", payload: String? = null) =
        (1..count).map { repo.save(Job("$name-$it", payload, Instant.now(), 3)) }

    @Test
    fun `claimBatch marks jobs RUNNING and assigns the worker`() {
        enqueue(5)

        val claimed = jobs.claimBatch("worker-A", 10)

        assertEquals(5, claimed.size)
        assertTrue(claimed.all { it.status == JobStatus.RUNNING })
        assertTrue(claimed.all { it.claimedBy == "worker-A" })
    }

    @Test
    fun `claimBatch respects the limit`() {
        enqueue(20)
        assertEquals(7, jobs.claimBatch("worker-A", 7).size)
        assertEquals(13, repo.countByStatus(JobStatus.PENDING))
    }

    @Test
    fun `claimBatch ignores jobs scheduled in the future`() {
        repo.save(Job("now", null, Instant.now(), 3))
        repo.save(Job("later", null, Instant.now().plusSeconds(600), 3))

        val claimed = jobs.claimBatch("worker-A", 10)

        assertEquals(1, claimed.size)
        assertEquals("now", claimed.first().name)
    }

    /**
     * The exactly-once guarantee under real contention.
     *
     * Eight threads race to claim from a queue of 200. Every job must be
     * claimed by exactly one worker: no duplicates (which would mean work
     * runs twice) and none missed (which would mean work is lost).
     */
    @Test
    fun `concurrent workers never claim the same job`() {
        val jobCount = 200
        val workerCount = 8
        enqueue(jobCount)

        val claimedIds = ConcurrentLinkedQueue<java.util.UUID>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(workerCount)
        val executor = Executors.newFixedThreadPool(workerCount)

        repeat(workerCount) { w ->
            executor.submit {
                start.await()                       // release all threads at once
                try {
                    while (true) {
                        val batch = jobs.claimBatch("worker-$w", 10)
                        if (batch.isEmpty()) break
                        batch.forEach { claimedIds.add(it.id) }
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish in time")
        executor.shutdown()

        val all = claimedIds.toList()
        assertEquals(jobCount, all.size, "some jobs were claimed more than once or lost")
        assertEquals(jobCount, all.toSet().size, "duplicate claims detected")
        assertEquals(0, repo.countByStatus(JobStatus.PENDING))
    }

    @Test
    fun `failure schedules a retry with backoff and increments attempts`() {
        val job = repo.save(Job("failing", "fail", Instant.now(), 3))

        jobs.markFailed(job.id, "boom")

        val after = repo.findById(job.id).orElseThrow()
        assertEquals(JobStatus.PENDING, after.status)
        assertEquals(1, after.attempts)
        assertTrue(after.runAt.isAfter(Instant.now()), "retry should be scheduled in the future")
        assertTrue(after.lastError!!.contains("boom"))
    }

    @Test
    fun `job becomes DEAD once attempts are exhausted`() {
        val job = repo.save(Job("doomed", "fail", Instant.now(), 2))

        jobs.markFailed(job.id, "first")
        jobs.markFailed(job.id, "second")

        val after = repo.findById(job.id).orElseThrow()
        assertEquals(JobStatus.DEAD, after.status)
        assertEquals(2, after.attempts)
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val first = JobService.backoffFor(0).seconds
        val second = JobService.backoffFor(1).seconds
        val third = JobService.backoffFor(2).seconds

        assertEquals(2L, first)
        assertEquals(4L, second)
        assertEquals(8L, third)
        assertEquals(600L, JobService.backoffFor(30).seconds, "should cap at 10 minutes")
    }

    /**
     * A crashed worker leaves jobs stuck in RUNNING. The reaper must return
     * them to PENDING so another worker picks them up.
     */
    @Test
    fun `reaper returns stale RUNNING jobs to PENDING`() {
        enqueue(3)
        jobs.claimBatch("crashed-worker", 10)
        assertEquals(3, repo.countByStatus(JobStatus.RUNNING))

        // Negative timeout: treat everything currently RUNNING as stale.
        val reaped = jobs.reapStale(java.time.Duration.ofSeconds(-1))

        assertEquals(3, reaped)
        assertEquals(3, repo.countByStatus(JobStatus.PENDING))
        assertEquals(0, repo.countByStatus(JobStatus.RUNNING))
    }

    @Test
    fun `succeeded jobs are terminal and clear the claim`() {
        val job = repo.save(Job("ok", null, Instant.now(), 3))
        jobs.claimBatch("worker-A", 1)

        jobs.markSucceeded(job.id)

        val after = repo.findById(job.id).orElseThrow()
        assertEquals(JobStatus.SUCCEEDED, after.status)
        assertEquals(null, after.claimedBy)
    }
}
