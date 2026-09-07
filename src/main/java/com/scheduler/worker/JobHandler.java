package com.scheduler.worker;

import com.scheduler.domain.Job;

/**
 * What actually runs when a job executes.
 *
 * <p>An interface rather than concrete logic so the scheduling machinery is
 * independent of the work: a real deployment swaps in a handler that sends
 * email or calls an API, and the tests swap in one that fails on demand to
 * exercise retry and backoff.
 *
 * <p>Contract: throwing signals failure and triggers retry. Handlers should
 * be idempotent, because a worker that crashes between doing the work and
 * committing SUCCEEDED will have the job retried.
 */
@FunctionalInterface
public interface JobHandler {
    void handle(Job job) throws Exception;
}
