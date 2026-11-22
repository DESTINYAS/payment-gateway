package com.payments.gateway.gateway;

/** Failure talking to the gateway. {@link #isRetryable()} says whether the charge may be retried. */
public class GatewayException extends RuntimeException {

    private final boolean retryable;

    public GatewayException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public GatewayException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
