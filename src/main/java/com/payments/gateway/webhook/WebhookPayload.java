package com.payments.gateway.webhook;

/**
 * Gateway callback body, after signature verification.
 *
 * @param type event type, e.g. {@code payment.succeeded} or {@code payment.failed}
 */
public record WebhookPayload(String eventId, String type, String reference, String gatewayReference,
                             long amountMinor, String currency) {
}
