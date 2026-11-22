package com.payments.gateway.payment;

import com.payments.gateway.common.Money;
import com.payments.gateway.error.Exceptions.DuplicateReference;
import com.payments.gateway.error.Exceptions.IdempotencyConflict;
import com.payments.gateway.error.Exceptions.UnknownReference;
import com.payments.gateway.gateway.GatewayClient;
import com.payments.gateway.gateway.GatewayException;
import com.payments.gateway.gateway.GatewayResponse;
import com.payments.gateway.gateway.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Creates payments, at most once per idempotency key.
 *
 * <p>The whole call runs in one database transaction: claim the key, insert the payment, call the
 * gateway (retrying transient failures), record the outcome, and complete the key. Either all of
 * that commits, or none of it does and the key is free to retry.
 *
 * <p>Outcome rules:
 * <ul>
 *   <li>Accepted: stays PENDING with the gateway reference. The webhook settles it later.</li>
 *   <li>Declined, or a non-retryable 4xx: FAILED. The charge is known not to exist.</li>
 *   <li>Retryable errors that never resolve: stays PENDING with {@code pendingReconciliation}.
 *       The charge may exist, so the payment must not be failed or re-charged under a new reference.</li>
 * </ul>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final IdempotencyStore idempotency;
    private final PaymentRepository payments;
    private final GatewayClient gateway;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public PaymentService(IdempotencyStore idempotency, PaymentRepository payments,
                          GatewayClient gateway, RetryPolicy retryPolicy, Clock clock) {
        this.idempotency = idempotency;
        this.payments = payments;
        this.gateway = gateway;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional
    public PaymentResult createPayment(String idempotencyKey, CreatePaymentRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        Money amount = request.money();   // validates amount and currency
        String fingerprint = fingerprint(request.reference(), amount);

        if (!idempotency.claim(idempotencyKey, fingerprint)) {
            IdempotencyStore.Stored stored = idempotency.find(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("claimed key disappeared: " + idempotencyKey));
            if (!stored.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflict(idempotencyKey);
            }
            log.info("replaying stored response for idempotency key {}", idempotencyKey);
            return stored.result();
        }

        if (payments.existsByReference(request.reference())) {
            throw new DuplicateReference(request.reference());
        }
        var now = clock.instant();
        Payment payment = payments.saveAndFlush(Payment.newPending(request.reference(), amount, now));

        boolean pendingReconciliation = false;
        try {
            GatewayResponse gw = retryPolicy.execute("charge[" + request.reference() + "]",
                    () -> gateway.charge(request.reference(), amount));
            if (gw.outcome() == GatewayResponse.Outcome.DECLINED) {
                payment.transitionTo(PaymentStatus.FAILED, now);
            } else if (gw.gatewayReference() != null) {
                payment.attachGatewayReference(gw.gatewayReference(), now);
            }
        } catch (GatewayException e) {
            if (e.isRetryable()) {
                pendingReconciliation = true;
                log.error("gateway outcome unknown for {} after retries; left PENDING for reconciliation",
                        request.reference(), e);
            } else {
                log.warn("gateway rejected {}: {}", request.reference(), e.getMessage());
                payment.transitionTo(PaymentStatus.FAILED, now);
            }
        }

        PaymentResult result = PaymentResult.of(payment, pendingReconciliation);
        idempotency.complete(idempotencyKey, result);
        return result;
    }

    @Transactional(readOnly = true)
    public PaymentResult getPayment(String reference) {
        return payments.findByReference(reference)
                .map(p -> PaymentResult.of(p, false))
                .orElseThrow(() -> new UnknownReference(reference));
    }

    static String fingerprint(String reference, Money amount) {
        String canonical = reference + "|" + amount.amountMinor() + "|" + amount.currency();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
