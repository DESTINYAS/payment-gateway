package com.payments.gateway.unit;

import com.payments.gateway.common.Money;
import com.payments.gateway.gateway.GatewayException;
import com.payments.gateway.gateway.RetryPolicy;
import com.payments.gateway.payment.Payment;
import com.payments.gateway.payment.PaymentStatus;
import com.payments.gateway.reconciliation.Reconciler;
import com.payments.gateway.reconciliation.Reconciler.DiscrepancyType;
import com.payments.gateway.reconciliation.Reconciler.PaymentSnapshot;
import com.payments.gateway.reconciliation.SettlementRecord;
import com.payments.gateway.error.Exceptions.InvalidTransition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryAndReconcileTest {

    private static final Instant T = Instant.parse("2026-11-22T10:00:00Z");

    @Test
    void retryableFailuresAreRetriedWithExponentialBackoffCappedAtMax() {
        List<Long> sleeps = new ArrayList<>();
        RetryPolicy policy = new RetryPolicy(5, 100, 2.0, 300, sleeps::add);
        AtomicInteger calls = new AtomicInteger();

        String result = policy.execute("op", () -> {
            if (calls.incrementAndGet() < 4) {
                throw new GatewayException("timeout", true);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(4, calls.get());
        assertEquals(List.of(100L, 200L, 300L), sleeps);   // 400 capped at 300
    }

    @Test
    void nonRetryableFailureIsThrownImmediately() {
        List<Long> sleeps = new ArrayList<>();
        RetryPolicy policy = new RetryPolicy(5, 10, 2.0, 100, sleeps::add);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(GatewayException.class, () -> policy.execute("op", () -> {
            calls.incrementAndGet();
            throw new GatewayException("rejected", false);
        }));
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void exhaustedRetriesRethrowTheLastFailure() {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy policy = new RetryPolicy(3, 0, 1.0, 0, ms -> { });

        GatewayException e = assertThrows(GatewayException.class, () -> policy.execute("op", () -> {
            throw new GatewayException("attempt " + calls.incrementAndGet(), true);
        }));
        assertEquals(3, calls.get());
        assertEquals("attempt 3", e.getMessage());
    }

    @Test
    void paymentStateMachineOnlyAllowsPendingToFinal() {
        Payment p = Payment.newPending("ref-1", new Money(100, "NGN"), T);
        p.transitionTo(PaymentStatus.SUCCEEDED, T);
        assertThrows(InvalidTransition.class, () -> p.transitionTo(PaymentStatus.FAILED, T));
        assertThrows(InvalidTransition.class, () -> p.transitionTo(PaymentStatus.PENDING, T));
    }

    @Test
    void reconcilerClassifiesEveryDiscrepancyKind() {
        List<PaymentSnapshot> internal = List.of(
                new PaymentSnapshot("ok", 100, "NGN", PaymentStatus.SUCCEEDED),
                new PaymentSnapshot("amt", 100, "NGN", PaymentStatus.SUCCEEDED),
                new PaymentSnapshot("sts", 100, "NGN", PaymentStatus.PENDING),
                new PaymentSnapshot("missing", 100, "NGN", PaymentStatus.SUCCEEDED),
                new PaymentSnapshot("dup", 100, "NGN", PaymentStatus.SUCCEEDED));
        List<SettlementRecord> settlement = List.of(
                new SettlementRecord("ok", "g1", 100, "NGN", PaymentStatus.SUCCEEDED),
                new SettlementRecord("amt", "g2", 999, "NGN", PaymentStatus.SUCCEEDED),
                new SettlementRecord("sts", "g3", 100, "NGN", PaymentStatus.SUCCEEDED),
                new SettlementRecord("ghost", "g4", 50, "NGN", PaymentStatus.SUCCEEDED),
                new SettlementRecord("dup", "g5", 100, "NGN", PaymentStatus.SUCCEEDED),
                new SettlementRecord("dup", "g5", 100, "NGN", PaymentStatus.SUCCEEDED));

        Reconciler.ReconciliationReport report = Reconciler.reconcile(internal, settlement);

        // "ok" and the first occurrence of "dup" match; the second "dup" is a duplicate.
        assertEquals(2, report.matched());
        List<DiscrepancyType> types = report.discrepancies().stream()
                .map(Reconciler.Discrepancy::type).toList();
        assertTrue(types.contains(DiscrepancyType.AMOUNT_MISMATCH));
        assertTrue(types.contains(DiscrepancyType.STATUS_MISMATCH));
        assertTrue(types.contains(DiscrepancyType.MISSING_INTERNAL));
        assertTrue(types.contains(DiscrepancyType.MISSING_IN_SETTLEMENT));
        assertTrue(types.contains(DiscrepancyType.DUPLICATE_SETTLEMENT));
        assertFalse(report.isClean());
    }

    @Test
    void cleanReconciliationHasNoDiscrepancies() {
        Reconciler.ReconciliationReport report = Reconciler.reconcile(
                List.of(new PaymentSnapshot("a", 5, "NGN", PaymentStatus.SUCCEEDED)),
                List.of(new SettlementRecord("a", "g", 5, "NGN", PaymentStatus.SUCCEEDED)));
        assertTrue(report.isClean());
        assertEquals(1, report.matched());
    }
}
