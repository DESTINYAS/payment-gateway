package com.payments.gateway.error;

/**
 * Domain exceptions, grouped in one file for brevity. Each is a {@link DomainException} with a
 * stable machine-readable code.
 */
public final class Exceptions {

    private Exceptions() {
    }

    /** Idempotency key reused with a different request body. */
    public static final class IdempotencyConflict extends DomainException {
        public IdempotencyConflict(String key) {
            super("IDEMPOTENCY_CONFLICT", "idempotency key reused with a different request: " + key);
        }
    }

    /** Merchant reference already used by another payment. */
    public static final class DuplicateReference extends DomainException {
        public DuplicateReference(String reference) {
            super("DUPLICATE_REFERENCE", "payment reference already exists: " + reference);
        }
    }

    /** No payment exists for the reference. */
    public static final class UnknownReference extends DomainException {
        public UnknownReference(String reference) {
            super("UNKNOWN_REFERENCE", "no payment found for reference: " + reference);
        }
    }

    /** Webhook signature missing, malformed, stale, or not matching. */
    public static final class InvalidSignature extends DomainException {
        public InvalidSignature(String reason) {
            super("INVALID_SIGNATURE", "invalid webhook signature: " + reason);
        }
    }

    /** Journal is unbalanced, malformed, or reuses a journal id with different content. */
    public static final class LedgerPosting extends DomainException {
        public LedgerPosting(String message) {
            super("LEDGER_POSTING_REJECTED", message);
        }
    }

    /** A status transition that the state machine forbids. */
    public static final class InvalidTransition extends DomainException {
        public InvalidTransition(String reference, Object from, Object to) {
            super("INVALID_TRANSITION", "illegal transition for " + reference + ": " + from + " -> " + to);
        }
    }
}
