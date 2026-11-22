package com.payments.gateway.it;

import com.payments.gateway.error.Exceptions.DuplicateReference;
import com.payments.gateway.error.Exceptions.IdempotencyConflict;
import com.payments.gateway.payment.CreatePaymentRequest;
import com.payments.gateway.payment.PaymentResult;
import com.payments.gateway.payment.PaymentService;
import com.payments.gateway.payment.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentIdempotencyIT extends IntegrationTestSupport {

    @Autowired
    private PaymentService payments;

    private static CreatePaymentRequest req(String reference, long amount) {
        return new CreatePaymentRequest(reference, amount, "NGN");
    }

    @Test
    void retryWithSameKeyAndBodyReplaysTheFirstResponseWithoutSecondCharge() {
        PaymentResult first = payments.createPayment("key-1", req("ref-1", 1000));
        PaymentResult second = payments.createPayment("key-1", req("ref-1", 1000));

        assertEquals(first, second);
        assertEquals(1, gateway.calls());
        assertEquals(1, count("SELECT COUNT(*) FROM payments"));
    }

    @Test
    void sameKeyWithDifferentBodyIsAConflict() {
        payments.createPayment("key-2", req("ref-2", 1000));
        assertThrows(IdempotencyConflict.class, () -> payments.createPayment("key-2", req("ref-2", 2000)));
        assertThrows(IdempotencyConflict.class, () -> payments.createPayment("key-2", req("ref-other", 1000)));
        assertEquals(1, gateway.calls());
    }

    @Test
    void concurrentRequestsWithOneKeyChargeTheGatewayExactlyOnce() throws Exception {
        gateway.setDelayMs(300);   // keep the first call in flight while the others arrive
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<PaymentResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> payments.createPayment("key-race", req("ref-race", 500)));
            }
            List<PaymentResult> results = new ArrayList<>();
            for (Future<PaymentResult> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                results.add(f.get());
            }
            for (PaymentResult r : results) {
                assertEquals(results.get(0), r);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, gateway.calls());
        assertEquals(1, count("SELECT COUNT(*) FROM payments WHERE reference = 'ref-race'"));
    }

    @Test
    void transientTimeoutsAreRetriedUntilTheGatewayAccepts() {
        gateway.enqueue(ScriptedGatewayClient.timeout(), ScriptedGatewayClient.timeout(),
                ScriptedGatewayClient.accepted());

        PaymentResult result = payments.createPayment("key-3", req("ref-3", 700));

        assertEquals(PaymentStatus.PENDING, result.status());
        assertNotNull(result.gatewayReference());
        assertFalse(result.pendingReconciliation());
        assertEquals(3, gateway.calls());
    }

    @Test
    void exhaustedTimeoutsLeavePaymentPendingForReconciliationAndReplayThatAnswer() {
        gateway.enqueue(ScriptedGatewayClient.timeout(), ScriptedGatewayClient.timeout(),
                ScriptedGatewayClient.timeout());

        PaymentResult result = payments.createPayment("key-4", req("ref-4", 700));
        PaymentResult replay = payments.createPayment("key-4", req("ref-4", 700));

        assertEquals(PaymentStatus.PENDING, result.status());
        assertTrue(result.pendingReconciliation());
        assertEquals(result, replay);
        assertEquals(3, gateway.calls());   // no new charge on replay
    }

    @Test
    void declinedAndRejectedChargesFailTheMerchantPayment() {
        gateway.enqueue(ScriptedGatewayClient.declined());
        assertEquals(PaymentStatus.FAILED, payments.createPayment("key-5", req("ref-5", 100)).status());

        gateway.enqueue(ScriptedGatewayClient.rejected());
        assertEquals(PaymentStatus.FAILED, payments.createPayment("key-6", req("ref-6", 100)).status());

        assertEquals(2, gateway.calls());   // a 4xx is never retried
    }

    @Test
    void invalidRequestRollsBackEverythingIncludingTheIdempotencyKey() {
        assertThrows(IllegalArgumentException.class,
                () -> payments.createPayment("key-7", req("ref-7", 0)));

        assertEquals(0, count("SELECT COUNT(*) FROM payments"));
        assertEquals(0, count("SELECT COUNT(*) FROM idempotency_keys"));
        assertEquals(0, gateway.calls());
    }

    @Test
    void reusedReferenceUnderANewKeyIsRejectedAndLeavesNoKeyBehind() {
        payments.createPayment("key-8a", req("ref-8", 100));

        assertThrows(DuplicateReference.class, () -> payments.createPayment("key-8b", req("ref-8", 100)));

        assertEquals(1, count("SELECT COUNT(*) FROM idempotency_keys"));
        assertEquals(1, gateway.calls());
    }
}
