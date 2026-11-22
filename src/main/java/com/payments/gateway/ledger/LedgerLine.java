package com.payments.gateway.ledger;

import java.util.Objects;

/** One side of a double-entry journal, before persistence. */
public record LedgerLine(String account, String currency, Direction direction, long amountMinor) {

    public enum Direction { DEBIT, CREDIT }

    public LedgerLine {
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("account must not be blank");
        }
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(direction, "direction");
    }

    public static LedgerLine debit(String account, String currency, long amountMinor) {
        return new LedgerLine(account, currency, Direction.DEBIT, amountMinor);
    }

    public static LedgerLine credit(String account, String currency, long amountMinor) {
        return new LedgerLine(account, currency, Direction.CREDIT, amountMinor);
    }
}
