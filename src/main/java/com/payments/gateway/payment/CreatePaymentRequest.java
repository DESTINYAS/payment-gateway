package com.payments.gateway.payment;

import com.payments.gateway.common.Money;

/** Merchant request body. The amount is validated when the {@link Money} is constructed. */
public record CreatePaymentRequest(String reference, long amountMinor, String currency) {

    public Money money() {
        return new Money(amountMinor, currency);
    }
}
