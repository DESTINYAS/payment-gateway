package com.payments.gateway.unit;

import com.payments.gateway.common.Money;
import com.payments.gateway.error.Exceptions.LedgerPosting;
import com.payments.gateway.ledger.JournalValidator;
import com.payments.gateway.ledger.LedgerLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyAndJournalTest {

    @Test
    void moneyRejectsZeroNegativeAndBadCurrency() {
        assertThrows(IllegalArgumentException.class, () -> new Money(0, "NGN"));
        assertThrows(IllegalArgumentException.class, () -> new Money(-1, "NGN"));
        assertThrows(IllegalArgumentException.class, () -> new Money(10, "ngn"));
        assertThrows(IllegalArgumentException.class, () -> new Money(10, "NAIRA"));
        assertEquals(new Money(500, "NGN"), new Money(500, "NGN"));
    }

    @Test
    void balancedJournalIsAccepted() {
        assertDoesNotThrow(() -> JournalValidator.validate("j1", List.of(
                LedgerLine.debit("A", "NGN", 100),
                LedgerLine.credit("B", "NGN", 60),
                LedgerLine.credit("C", "NGN", 40))));
    }

    @Test
    void unbalancedJournalIsRejected() {
        assertThrows(LedgerPosting.class, () -> JournalValidator.validate("j2", List.of(
                LedgerLine.debit("A", "NGN", 100),
                LedgerLine.credit("B", "NGN", 99))));
    }

    @Test
    void singleLineMixedCurrencyAndNonPositiveLinesAreRejected() {
        assertThrows(LedgerPosting.class, () -> JournalValidator.validate("j3",
                List.of(LedgerLine.debit("A", "NGN", 100))));
        assertThrows(LedgerPosting.class, () -> JournalValidator.validate("j4", List.of(
                LedgerLine.debit("A", "NGN", 100),
                LedgerLine.credit("B", "USD", 100))));
        assertThrows(LedgerPosting.class, () -> JournalValidator.validate("j5", List.of(
                LedgerLine.debit("A", "NGN", 0),
                LedgerLine.credit("B", "NGN", 0))));
        assertThrows(LedgerPosting.class, () -> JournalValidator.validate(" ", List.of(
                LedgerLine.debit("A", "NGN", 1),
                LedgerLine.credit("B", "NGN", 1))));
    }
}
