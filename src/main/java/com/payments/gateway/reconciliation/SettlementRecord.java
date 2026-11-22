package com.payments.gateway.reconciliation;

import com.payments.gateway.payment.PaymentStatus;

/** One line of the gateway's settlement report. */
public record SettlementRecord(String reference, String gatewayReference, long amountMinor,
                               String currency, PaymentStatus status) {
}
