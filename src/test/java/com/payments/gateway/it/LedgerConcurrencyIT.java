package com.payments.gateway.it;

import com.payments.gateway.error.Exceptions.LedgerPosting;
import com.payments.gateway.ledger.LedgerLine;
import com.payments.gateway.ledger.LedgerService;
import com.payments.gateway.ledger.LedgerService.PostOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerConcurrencyIT extends IntegrationTestSupport {

    @Autowired
    private LedgerService ledger;

    @Test
    void concurrentJournalsOnTheSameAccountsKeepBalancesExact() throws Exception {
        int threads = 20;
        int perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                tasks.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        // Opposite directions on the same pair of accounts: this is where lock ordering matters.
                        ledger.post("a-" + id + "-" + i, List.of(
                                LedgerLine.debit("A", "NGN", 1), LedgerLine.credit("B", "NGN", 1)));
                        ledger.post("b-" + id + "-" + i, List.of(
                                LedgerLine.debit("B", "NGN", 2), LedgerLine.credit("A", "NGN", 2)));
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks, 120, TimeUnit.SECONDS)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        long journals = threads * perThread * 2L;
        assertEquals(journals, count("SELECT COUNT(*) FROM ledger_journals"));
        // A: -1 per first-type journal, +2 per second-type journal. B is the mirror image.
        long expectedA = (long) threads * perThread * (2 - 1);
        assertEquals(expectedA, balance("A"));
        assertEquals(-expectedA, balance("B"));
        ledger.assertBalanced();
    }

    @Test
    void identicalJournalIdPostedConcurrentlyIsAppliedExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<PostOutcome> outcomes = new ArrayList<>();
        try {
            List<Callable<PostOutcome>> tasks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tasks.add(() -> ledger.post("same-journal", List.of(
                        LedgerLine.debit("X", "NGN", 50), LedgerLine.credit("Y", "NGN", 50))));
            }
            for (Future<PostOutcome> f : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                outcomes.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, outcomes.stream().filter(o -> o == PostOutcome.APPLIED).count());
        assertEquals(50, balance("Y"));
        assertEquals(-50, balance("X"));
    }

    @Test
    void replayOfIdenticalJournalIsANoOp() {
        List<LedgerLine> lines = List.of(LedgerLine.debit("P", "NGN", 10), LedgerLine.credit("Q", "NGN", 10));
        assertEquals(PostOutcome.APPLIED, ledger.post("j-1", lines));
        assertEquals(PostOutcome.REPLAYED, ledger.post("j-1", lines));
        assertEquals(10, balance("Q"));
    }

    @Test
    void reusingAJournalIdWithDifferentContentIsRejected() {
        ledger.post("j-2", List.of(LedgerLine.debit("P", "NGN", 10), LedgerLine.credit("Q", "NGN", 10)));

        assertThrows(LedgerPosting.class, () -> ledger.post("j-2", List.of(
                LedgerLine.debit("P", "NGN", 20), LedgerLine.credit("Q", "NGN", 20))));
        assertEquals(10, balance("Q"));
    }

    @Test
    void unbalancedJournalWritesNothing() {
        assertThrows(LedgerPosting.class, () -> ledger.post("j-3", List.of(
                LedgerLine.debit("P", "NGN", 10), LedgerLine.credit("Q", "NGN", 9))));

        assertEquals(0, count("SELECT COUNT(*) FROM ledger_journals"));
        assertEquals(0, count("SELECT COUNT(*) FROM ledger_accounts"));
    }
}
