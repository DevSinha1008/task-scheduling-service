package com.scheduler.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The worker thread pool.
 *
 * <p>{@code @EnableScheduling} switches on @Scheduled processing. Without it
 * the annotations are inert and the worker silently never runs.
 */
@Configuration
@EnableScheduling
public class ExecutorConfig {

    @Bean
    public ThreadPoolExecutor jobExecutor(
            @Value("${app.worker.pool-size:8}") int poolSize,
            @Value("${app.worker.queue-capacity:200}") int queueCapacity) {

        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "job-worker-" + counter.incrementAndGet());
            // Daemon threads do not hold the JVM open at shutdown. In-flight
            // jobs are reaped back to PENDING by the reaper, so losing them
            // on shutdown is recoverable; a hung JVM is not.
            t.setDaemon(true);
            return t;
        };

        return new ThreadPoolExecutor(
                poolSize, poolSize,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),  // BOUNDED, deliberately
                factory,
                // Backpressure. The alternative default (AbortPolicy) drops
                // the job, and an unbounded queue would let memory grow until
                // the process dies. CallerRunsPolicy instead makes the
                // polling thread execute the task itself, which stops it
                // polling for more work — the queue drains and the pressure
                // propagates back to the source rather than being absorbed.
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
