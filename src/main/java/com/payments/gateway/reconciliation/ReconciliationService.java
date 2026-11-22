package com.payments.gateway.reconciliation;

import com.payments.gateway.payment.Payment;
import com.payments.gateway.payment.PaymentRepository;
import com.payments.gateway.reconciliation.Reconciler.PaymentSnapshot;
import com.payments.gateway.reconciliation.Reconciler.ReconciliationReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReconciliationService {

    private final PaymentRepository payments;

    public ReconciliationService(PaymentRepository payments) {
        this.payments = payments;
    }

    /** Compares all stored payments with a settlement report. Read-only. */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(List<SettlementRecord> settlement) {
        List<PaymentSnapshot> internal = payments.findAll().stream()
                .map(ReconciliationService::snapshot)
                .toList();
        return Reconciler.reconcile(internal, settlement);
    }

    private static PaymentSnapshot snapshot(Payment p) {
        return new PaymentSnapshot(p.getReference(), p.amount().amountMinor(),
                p.amount().currency(), p.getStatus());
    }
}
