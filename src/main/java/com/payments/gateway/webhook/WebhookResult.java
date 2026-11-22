package com.payments.gateway.webhook;

/** What happened to a verified webhook. Each is a normal outcome, so the gateway should not retry it. */
public enum WebhookResult {
    /** The event moved a PENDING payment and posted its ledger journal. */
    APPLIED,
    /** This event id was already recorded. The redelivery changed nothing. */
    DUPLICATE,
    /** The payment is already in the target state. */
    IGNORED_ALREADY_FINAL,
    /** The payment is final in the opposite state. The first final state stands. */
    IGNORED_CONFLICTING_FINAL_STATE,
    /** Event type is not handled. */
    IGNORED_UNSUPPORTED_TYPE,
    /** Amount or currency differs from the stored payment. Nothing changed; needs investigation. */
    REJECTED_AMOUNT_MISMATCH,
    /** Gateway reference differs from the one stored for this payment. Nothing changed. */
    REJECTED_GATEWAY_REFERENCE_MISMATCH
}
