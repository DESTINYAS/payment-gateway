package com.payments.gateway.ledger;

import com.payments.gateway.error.Exceptions.LedgerPosting;

import java.util.List;

/**
 * Pure validation of a journal. A journal is acceptable only if it has at least two lines, one
 * currency throughout, strictly positive amounts, and debits equal to credits.
 */
public final class JournalValidator {

    private JournalValidator() {
    }

    public static void validate(String journalId, List<LedgerLine> lines) {
        if (journalId == null || journalId.isBlank()) {
            throw new LedgerPosting("journalId must not be blank");
        }
        if (lines == null || lines.size() < 2) {
            throw new LedgerPosting("journal " + journalId + " needs at least two lines");
        }
        String currency = lines.get(0).currency();
        long debits = 0;
        long credits = 0;
        for (LedgerLine line : lines) {
            if (!currency.equals(line.currency())) {
                throw new LedgerPosting("journal " + journalId + " mixes currencies");
            }
            if (line.amountMinor() <= 0) {
                throw new LedgerPosting("journal " + journalId + " has a non-positive line");
            }
            if (line.direction() == LedgerLine.Direction.DEBIT) {
                debits = Math.addExact(debits, line.amountMinor());
            } else {
                credits = Math.addExact(credits, line.amountMinor());
            }
        }
        if (debits != credits) {
            throw new LedgerPosting("journal " + journalId + " is unbalanced: debits=" + debits
                    + " credits=" + credits);
        }
    }
}
