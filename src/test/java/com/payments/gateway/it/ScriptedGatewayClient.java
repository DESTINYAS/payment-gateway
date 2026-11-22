package com.payments.gateway.it;

import com.payments.gateway.common.Money;
import com.payments.gateway.gateway.GatewayClient;
import com.payments.gateway.gateway.GatewayException;
import com.payments.gateway.gateway.GatewayResponse;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Test double for the upstream gateway. Each call consumes the next scripted behaviour; when the
 * script is empty it accepts the charge. Every call is counted, so tests can assert "charged once".
 */
public class ScriptedGatewayClient implements GatewayClient {

    private final Deque<Supplier<GatewayResponse>> script = new ConcurrentLinkedDeque<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile long delayMs = 0;

    public static Supplier<GatewayResponse> accepted() {
        return () -> new GatewayResponse(GatewayResponse.Outcome.ACCEPTED, "gw-" + System.nanoTime());
    }

    public static Supplier<GatewayResponse> declined() {
        return () -> new GatewayResponse(GatewayResponse.Outcome.DECLINED, "gw-declined");
    }

    /** Simulates a network timeout: retryable, the charge may or may not have happened. */
    public static Supplier<GatewayResponse> timeout() {
        return () -> {
            throw new GatewayException("simulated timeout", true);
        };
    }

    /** Simulates a definitive 4xx rejection: not retryable. */
    public static Supplier<GatewayResponse> rejected() {
        return () -> {
            throw new GatewayException("simulated 4xx", false);
        };
    }

    public void enqueue(Supplier<GatewayResponse>... behaviours) {
        for (Supplier<GatewayResponse> b : behaviours) {
            script.addLast(b);
        }
    }

    /** Widens the race window for concurrency tests. */
    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        script.clear();
        calls.set(0);
        delayMs = 0;
    }

    @Override
    public GatewayResponse charge(String reference, Money amount) {
        calls.incrementAndGet();
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GatewayException("interrupted", true, e);
            }
        }
        Supplier<GatewayResponse> next = script.pollFirst();
        return (next != null ? next : accepted()).get();
    }
}
