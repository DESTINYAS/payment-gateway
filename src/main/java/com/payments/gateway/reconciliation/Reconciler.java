package com.payments.gateway.reconciliation;

import com.payments.gateway.payment.PaymentStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure comparison of internal payments against the gateway's settlement report. No I/O and no
 * clock, so it is deterministic and easy to test.
 */
public final class Reconciler {

    public enum DiscrepancyType {
        /** Settled by the gateway, but we have no payment for that reference. */
        MISSING_INTERNAL,
        /** We record SUCCEEDED, but the gateway did not settle it. */
        MISSING_IN_SETTLEMENT,
        /** Settled amount or currency differs from ours. */
        AMOUNT_MISMATCH,
        /** Settled status differs from ours. */
        STATUS_MISMATCH,
        /** The same reference appears more than once in the settlement report. */
        DUPLICATE_SETTLEMENT
    }

    public record PaymentSnapshot(String reference, long amountMinor, String currency, PaymentStatus status) {
    }

    public record Discrepancy(DiscrepancyType type, String reference, String detail) {
    }

    public record ReconciliationReport(int matched, List<Discrepancy> discrepancies) {
        public boolean isClean() {
            return discrepancies.isEmpty();
        }
    }

    private Reconciler() {
    }

    public static ReconciliationReport reconcile(List<PaymentSnapshot> internal,
                                                 List<SettlementRecord> settlement) {
        Map<String, PaymentSnapshot> byReference = new HashMap<>();
        for (PaymentSnapshot p : internal) {
            byReference.put(p.reference(), p);
        }

        List<Discrepancy> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int matched = 0;

        for (SettlementRecord record : settlement) {
            if (!seen.add(record.reference())) {
                found.add(new Discrepancy(DiscrepancyType.DUPLICATE_SETTLEMENT, record.reference(),
                        "reference appears more than once in settlement"));
                continue;
            }
            PaymentSnapshot ours = byReference.get(record.reference());
            if (ours == null) {
                found.add(new Discrepancy(DiscrepancyType.MISSING_INTERNAL, record.reference(),
                        "settled by gateway (" + record.gatewayReference() + ") with no internal payment"));
            } else if (ours.amountMinor() != record.amountMinor() || !ours.currency().equals(record.currency())) {
                found.add(new Discrepancy(DiscrepancyType.AMOUNT_MISMATCH, record.reference(),
                        "internal " + ours.amountMinor() + " " + ours.currency()
                                + " vs settled " + record.amountMinor() + " " + record.currency()));
            } else if (ours.status() != record.status()) {
                found.add(new Discrepancy(DiscrepancyType.STATUS_MISMATCH, record.reference(),
                        "internal " + ours.status() + " vs settled " + record.status()));
            } else {
                matched++;
            }
        }

        for (PaymentSnapshot p : internal) {
            if (p.status() == PaymentStatus.SUCCEEDED && !seen.contains(p.reference())) {
                found.add(new Discrepancy(DiscrepancyType.MISSING_IN_SETTLEMENT, p.reference(),
                        "recorded SUCCEEDED but absent from settlement report"));
            }
        }
        return new ReconciliationReport(matched, found);
    }
}
