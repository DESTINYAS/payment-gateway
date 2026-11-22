package com.payments.gateway.payment;

/**
 * Response returned to the merchant and replayed for the same idempotency key.
 *
 * @param pendingReconciliation true when the gateway outcome is unknown after retries. The
 *                              payment stays PENDING until reconciliation resolves it.
 */
public record PaymentResult(String reference, PaymentStatus status, String gatewayReference,
                            boolean pendingReconciliation) {

    public static PaymentResult of(Payment payment, boolean pendingReconciliation) {
        return new PaymentResult(payment.getReference(), payment.getStatus(),
                payment.getGatewayReference(), pendingReconciliation);
    }
}
