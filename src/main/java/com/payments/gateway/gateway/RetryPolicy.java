package com.payments.gateway.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Exponential backoff for retryable {@link GatewayException}s only. Non-retryable failures are
 * rethrown at once. The sleeper is injectable so tests run without waiting.
 */
public final class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final int maxAttempts;
    private final long initialBackoffMs;
    private final double multiplier;
    private final long maxBackoffMs;
    private final Sleeper sleeper;

    public RetryPolicy(int maxAttempts, long initialBackoffMs, double multiplier,
                       long maxBackoffMs, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (initialBackoffMs < 0 || maxBackoffMs < 0 || multiplier < 1.0) {
            throw new IllegalArgumentException("invalid backoff configuration");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.multiplier = multiplier;
        this.maxBackoffMs = maxBackoffMs;
        this.sleeper = sleeper;
    }

    public static RetryPolicy of(int maxAttempts, long initialBackoffMs, double multiplier, long maxBackoffMs) {
        return new RetryPolicy(maxAttempts, initialBackoffMs, multiplier, maxBackoffMs, Thread::sleep);
    }

    public <T> T execute(String operation, Supplier<T> attempt) {
        long backoff = initialBackoffMs;
        for (int n = 1; ; n++) {
            try {
                return attempt.get();
            } catch (GatewayException e) {
                if (!e.isRetryable() || n >= maxAttempts) {
                    throw e;
                }
                log.warn("{} attempt {}/{} failed (retryable): {}; retrying in {} ms",
                        operation, n, maxAttempts, e.getMessage(), backoff);
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoff = Math.min(maxBackoffMs, (long) Math.ceil(backoff * multiplier));
            }
        }
    }
}
