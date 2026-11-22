package com.payments.gateway.payment;

/** PENDING may move to SUCCEEDED or FAILED. Both are terminal. Nothing else is legal. */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this != PENDING;
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return this == PENDING && next != null && next != PENDING;
    }
}
