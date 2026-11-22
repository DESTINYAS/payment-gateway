package com.payments.gateway.common;

import java.util.Objects;

/**
 * Amount in minor units (kobo, cents) of an ISO-4217 currency. Never a floating-point type.
 * Strictly positive: zero or negative amounts are rejected at construction.
 */
public record Money(long amountMinor, String currency) {

    public Money {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amountMinor);
        }
        Objects.requireNonNull(currency, "currency");
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be ISO-4217 alpha-3: " + currency);
        }
    }
}
