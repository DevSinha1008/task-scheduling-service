package com.scheduler.worker;

import com.scheduler.domain.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in handler used when no domain-specific one is supplied.
 *
 * <p>Two conventions exist to make the failure paths testable end to end:
 * a payload containing "fail" always throws, and one containing "slow"
 * sleeps. Without a way to provoke failure on demand, retry and backoff can
 * only be unit-tested in isolation, never through the real worker loop.
 *
 * <p>{@code @ConditionalOnMissingBean} means defining any other JobHandler
 * bean silently replaces this one.
 */
@Component
public class DefaultJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobHandler.class);

    @Override
    public void handle(Job job) throws Exception {
        String payload = job.getPayload() == null ? "" : job.getPayload();

        if (payload.contains("\"fail\"") || payload.contains("fail")) {
            throw new IllegalStateException("handler asked to fail: " + job.getName());
        }
        if (payload.contains("slow")) {
            Thread.sleep(250);
        }
        log.debug("executed job {} ({})", job.getId(), job.getName());
    }
}
