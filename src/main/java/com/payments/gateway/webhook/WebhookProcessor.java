package com.payments.gateway.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.gateway.error.Exceptions.UnknownReference;
import com.payments.gateway.ledger.LedgerAccounts;
import com.payments.gateway.ledger.LedgerLine;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.payment.Payment;
import com.payments.gateway.payment.PaymentRepository;
import com.payments.gateway.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Applies gateway webhooks exactly once.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Verify the signature over the raw bytes. Nothing is parsed or stored before this.</li>
 *   <li>In one transaction: claim the event id in {@code webhook_events}
 *       ({@code ON CONFLICT DO NOTHING}), lock the payment row, validate amount and gateway
 *       reference, post the ledger journal on success, transition the status, and record the outcome.</li>
 * </ol>
 *
 * <p>Failure handling: if anything throws, the transaction rolls back. That includes the event
 * claim, so the gateway's redelivery is processed from scratch. A crash mid-way leaves no
 * half-applied state.
 *
 * <p>Concurrency: two deliveries of the same event serialise on the event-id unique index. The
 * second sees zero rows inserted and returns DUPLICATE. Deliveries for the same payment serialise
 * on its row lock.
 */
@Service
public class WebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessor.class);

    private final WebhookSignatureVerifier verifier;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WebhookProcessor(WebhookSignatureVerifier verifier, ObjectMapper objectMapper,
                            JdbcTemplate jdbc, PaymentRepository payments, LedgerService ledger,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.verifier = verifier;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.payments = payments;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public WebhookResult process(String rawBody, String signatureHeader) {
        verifier.verify(rawBody, signatureHeader);
        WebhookPayload payload = parse(rawBody);
        return tx.execute(status -> apply(payload));
    }

    WebhookPayload parse(String rawBody) {
        try {
            WebhookPayload payload = objectMapper.readValue(rawBody, WebhookPayload.class);
            if (payload.eventId() == null || payload.eventId().isBlank()
                    || payload.reference() == null || payload.reference().isBlank()
                    || payload.type() == null) {
                throw new IllegalArgumentException("webhook payload is missing required fields");
            }
            return payload;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("malformed webhook payload", e);
        }
    }

    private WebhookResult apply(WebhookPayload event) {
        int claimed = jdbc.update(
                "INSERT INTO webhook_events (event_id, reference, event_type, outcome) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (event_id) DO NOTHING",
                event.eventId(), event.reference(), event.type(), "RECEIVED");
        if (claimed == 0) {
            log.info("webhook event {} already processed", event.eventId());
            return WebhookResult.DUPLICATE;
        }

        WebhookResult result = decide(event);
        jdbc.update("UPDATE webhook_events SET outcome = ? WHERE event_id = ?",
                result.name(), event.eventId());
        return result;
    }

    private WebhookResult decide(WebhookPayload event) {
        PaymentStatus target = switch (event.type()) {
            case "payment.succeeded" -> PaymentStatus.SUCCEEDED;
            case "payment.failed" -> PaymentStatus.FAILED;
            default -> null;
        };
        if (target == null) {
            log.info("ignoring unsupported webhook type {} for {}", event.type(), event.reference());
            return WebhookResult.IGNORED_UNSUPPORTED_TYPE;
        }

        Payment payment = payments.findForUpdate(event.reference())
                .orElseThrow(() -> new UnknownReference(event.reference()));

        if (payment.amount().amountMinor() != event.amountMinor()
                || !payment.amount().currency().equals(event.currency())) {
            log.error("amount mismatch on {}: stored={} webhook={} {}", event.reference(),
                    payment.amount(), event.amountMinor(), event.currency());
            return WebhookResult.REJECTED_AMOUNT_MISMATCH;
        }
        if (payment.getGatewayReference() != null && event.gatewayReference() != null
                && !payment.getGatewayReference().equals(event.gatewayReference())) {
            log.error("gateway reference mismatch on {}", event.reference());
            return WebhookResult.REJECTED_GATEWAY_REFERENCE_MISMATCH;
        }
        if (payment.getStatus() == target) {
            return WebhookResult.IGNORED_ALREADY_FINAL;
        }
        if (payment.getStatus().isTerminal()) {
            log.error("conflicting event {} for {} already {}; keeping first final state",
                    target, event.reference(), payment.getStatus());
            return WebhookResult.IGNORED_CONFLICTING_FINAL_STATE;
        }

        if (target == PaymentStatus.SUCCEEDED) {
            // Same transaction as the status change: both commit or neither does.
            ledger.post("settlement:" + event.reference(), settlementLines(payment));
        }
        payment.transitionTo(target, clock.instant());
        return WebhookResult.APPLIED;
    }

    /** Settlement: the gateway owes us the amount (debit receivable), and the merchant is owed it (credit payable). */
    private static List<LedgerLine> settlementLines(Payment payment) {
        String currency = payment.amount().currency();
        long amount = payment.amount().amountMinor();
        return List.of(
                LedgerLine.debit(LedgerAccounts.GATEWAY_RECEIVABLE, currency, amount),
                LedgerLine.credit(LedgerAccounts.MERCHANT_PAYABLE, currency, amount));
    }
}
