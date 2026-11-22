package com.payments.gateway.it;

import com.payments.gateway.error.Exceptions.InvalidSignature;
import com.payments.gateway.error.Exceptions.UnknownReference;
import com.payments.gateway.ledger.LedgerAccounts;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.payment.CreatePaymentRequest;
import com.payments.gateway.payment.PaymentService;
import com.payments.gateway.payment.PaymentStatus;
import com.payments.gateway.webhook.WebhookProcessor;
import com.payments.gateway.webhook.WebhookResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookDeliveryIT extends IntegrationTestSupport {

    @Autowired
    private PaymentService payments;

    @Autowired
    private WebhookProcessor webhooks;

    @Autowired
    private LedgerService ledger;

    private void createPending(String reference, long amount) {
        payments.createPayment("k-" + reference, new CreatePaymentRequest(reference, amount, "NGN"));
    }

    private WebhookResult deliver(String eventId, String type, String reference, long amount) {
        String[] delivery = signedWebhook(eventId, type, reference, amount);
        return webhooks.process(delivery[0], delivery[1]);
    }

    @Test
    void successfulWebhookSettlesThePaymentAndPostsBalancedLedger() {
        createPending("p1", 1500);

        assertEquals(WebhookResult.APPLIED, deliver("evt-1", "payment.succeeded", "p1", 1500));

        assertEquals(PaymentStatus.SUCCEEDED, payments.getPayment("p1").status());
        assertEquals(-1500, balance(LedgerAccounts.GATEWAY_RECEIVABLE));
        assertEquals(1500, balance(LedgerAccounts.MERCHANT_PAYABLE));
        ledger.assertBalanced();
    }

    @Test
    void redeliveredEventIsDuplicateAndNeverDoubleCredits() {
        createPending("p2", 1500);
        assertEquals(WebhookResult.APPLIED, deliver("evt-2", "payment.succeeded", "p2", 1500));

        assertEquals(WebhookResult.DUPLICATE, deliver("evt-2", "payment.succeeded", "p2", 1500));
        assertEquals(WebhookResult.IGNORED_ALREADY_FINAL, deliver("evt-2b", "payment.succeeded", "p2", 1500));

        assertEquals(1500, balance(LedgerAccounts.MERCHANT_PAYABLE));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_journals"));
        ledger.assertBalanced();
    }

    @Test
    void concurrentDeliveriesOfTheSameEventApplyExactlyOnce() throws Exception {
        createPending("p3", 900);
        String[] delivery = signedWebhook("evt-3", "payment.succeeded", "p3", 900);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<WebhookResult> results = new ArrayList<>();
        try {
            List<Callable<WebhookResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tasks.add(() -> webhooks.process(delivery[0], delivery[1]));
            }
            for (Future<WebhookResult> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                results.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, results.stream().filter(r -> r == WebhookResult.APPLIED).count());
        assertEquals(9, results.stream().filter(r -> r == WebhookResult.DUPLICATE).count());
        assertEquals(900, balance(LedgerAccounts.MERCHANT_PAYABLE));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_journals"));
        ledger.assertBalanced();
    }

    @Test
    void conflictingFinalEventIsIgnoredAndTheFirstFinalStateWins() {
        createPending("p4", 300);
        deliver("evt-4a", "payment.succeeded", "p4", 300);

        assertEquals(WebhookResult.IGNORED_CONFLICTING_FINAL_STATE,
                deliver("evt-4b", "payment.failed", "p4", 300));
        assertEquals(PaymentStatus.SUCCEEDED, payments.getPayment("p4").status());
    }

    @Test
    void amountMismatchIsRejectedWithoutAnyStateChange() {
        createPending("p5", 1000);

        assertEquals(WebhookResult.REJECTED_AMOUNT_MISMATCH,
                deliver("evt-5", "payment.succeeded", "p5", 999));

        assertEquals(PaymentStatus.PENDING, payments.getPayment("p5").status());
        assertEquals(0, count("SELECT COUNT(*) FROM ledger_journals"));
    }

    @Test
    void badSignatureIsRejectedBeforeAnythingIsStored() {
        createPending("p6", 100);
        String body = "{\"eventId\":\"evt-6\",\"type\":\"payment.succeeded\",\"reference\":\"p6\","
                + "\"amountMinor\":100,\"currency\":\"NGN\"}";

        assertThrows(InvalidSignature.class, () -> webhooks.process(body, "t=1,v1=00"));
        assertThrows(InvalidSignature.class, () -> webhooks.process(body, null));

        assertEquals(0, count("SELECT COUNT(*) FROM webhook_events"));
        assertEquals(PaymentStatus.PENDING, payments.getPayment("p6").status());
    }

    @Test
    void unknownReferenceRollsBackTheEventSoTheGatewayRedeliveryIsProcessedLater() {
        String[] delivery = signedWebhook("evt-7", "payment.succeeded", "late", 400);

        assertThrows(UnknownReference.class, () -> webhooks.process(delivery[0], delivery[1]));
        assertEquals(0, count("SELECT COUNT(*) FROM webhook_events"));

        // The payment shows up later (or the write was lost) and the gateway redelivers.
        createPending("late", 400);
        assertEquals(WebhookResult.APPLIED, webhooks.process(delivery[0], delivery[1]));
        assertEquals(PaymentStatus.SUCCEEDED, payments.getPayment("late").status());
    }

    @Test
    void failedEventFailsThePaymentAndPostsNoLedgerJournal() {
        createPending("p8", 250);

        assertEquals(WebhookResult.APPLIED, deliver("evt-8", "payment.failed", "p8", 250));

        assertEquals(PaymentStatus.FAILED, payments.getPayment("p8").status());
        assertEquals(0, count("SELECT COUNT(*) FROM ledger_journals"));
    }

    @Test
    void unsupportedEventTypeIsAcknowledgedButIgnored() {
        createPending("p9", 250);

        assertEquals(WebhookResult.IGNORED_UNSUPPORTED_TYPE, deliver("evt-9", "payment.refunded", "p9", 250));
        assertEquals(PaymentStatus.PENDING, payments.getPayment("p9").status());
    }
}
