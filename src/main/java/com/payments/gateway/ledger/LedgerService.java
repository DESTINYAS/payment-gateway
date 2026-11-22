package com.payments.gateway.ledger;

import com.payments.gateway.error.Exceptions.LedgerPosting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Double-entry ledger posting.
 *
 * <p>Atomicity: {@link #post} joins the caller's transaction, so a journal is either fully written
 * with its balance updates or not at all.
 *
 * <p>Idempotency: the {@code ledger_journals} primary key is claimed with
 * {@code INSERT ... ON CONFLICT DO NOTHING} before anything else. Replaying an identical journal
 * is a no-op. Reusing a journal id with different lines is rejected.
 *
 * <p>Concurrency: accounts are created if missing, then row-locked with {@code FOR UPDATE} in
 * sorted (code, currency) order. Locking in one global order prevents deadlocks between
 * concurrent journals that touch the same accounts.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    public enum PostOutcome { APPLIED, REPLAYED }

    private final JdbcTemplate jdbc;
    private final LedgerAccountRepository accounts;

    public LedgerService(JdbcTemplate jdbc, LedgerAccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Transactional
    public PostOutcome post(String journalId, List<LedgerLine> lines) {
        JournalValidator.validate(journalId, lines);

        int claimed = jdbc.update(
                "INSERT INTO ledger_journals (journal_id) VALUES (?) ON CONFLICT (journal_id) DO NOTHING",
                journalId);
        if (claimed == 0) {
            if (!storedLines(journalId).equals(lines)) {
                throw new LedgerPosting("journal id reused with different content: " + journalId);
            }
            log.info("ledger journal {} already posted; replay ignored", journalId);
            return PostOutcome.REPLAYED;
        }

        // 1. Ensure every account exists (no-op if present).
        TreeSet<String> keys = new TreeSet<>();
        for (LedgerLine line : lines) {
            keys.add(line.account() + "|" + line.currency());
        }
        for (String key : keys) {
            String[] parts = key.split("\\|", 2);
            jdbc.update("INSERT INTO ledger_accounts (code, currency) VALUES (?, ?) "
                    + "ON CONFLICT (code, currency) DO NOTHING", parts[0], parts[1]);
        }

        // 2. Lock all touched accounts in sorted order, keeping them for balance updates.
        List<LedgerAccount> locked = new ArrayList<>(keys.size());
        for (String key : keys) {
            String[] parts = key.split("\\|", 2);
            locked.add(accounts.findForUpdate(parts[0], parts[1])
                    .orElseThrow(() -> new IllegalStateException("account vanished: " + key)));
        }

        // 3. Write lines and apply balance deltas.
        int lineNo = 1;
        for (LedgerLine line : lines) {
            LedgerAccount account = locked.stream()
                    .filter(a -> a.getCode().equals(line.account()) && a.getCurrency().equals(line.currency()))
                    .findFirst().orElseThrow();
            jdbc.update("INSERT INTO ledger_entries (journal_id, line_no, account_id, direction, amount_minor) "
                    + "VALUES (?, ?, ?, ?, ?)",
                    journalId, lineNo++, account.getId(), line.direction().name(), line.amountMinor());
            account.apply(line.direction(), line.amountMinor());
        }
        log.info("ledger journal {} posted ({} lines)", journalId, lines.size());
        return PostOutcome.APPLIED;
    }

    /** Balance of an account: credits minus debits, in minor units. */
    @Transactional(readOnly = true)
    public long balanceOf(String account, String currency) {
        return accounts.findAll().stream()
                .filter(a -> a.getCode().equals(account) && a.getCurrency().equals(currency))
                .findFirst()
                .map(LedgerAccount::getBalanceMinor)
                .orElse(0L);
    }

    /**
     * Global invariant: for every currency, total debits equal total credits, and each account's
     * stored balance equals the sum of its entries.
     *
     * @throws LedgerPosting if the books are out of balance
     */
    @Transactional(readOnly = true)
    public void assertBalanced() {
        Long unbalanced = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT e.journal_id FROM ledger_entries e "
                + "GROUP BY e.journal_id HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor "
                + "ELSE -e.amount_minor END) <> 0) x", Long.class);
        if (unbalanced != null && unbalanced > 0) {
            throw new LedgerPosting("ledger has " + unbalanced + " unbalanced journal(s)");
        }
        Long mismatched = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_accounts a WHERE a.balance_minor <> ("
                + "SELECT COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor "
                + "ELSE -e.amount_minor END), 0) FROM ledger_entries e WHERE e.account_id = a.id)", Long.class);
        if (mismatched != null && mismatched > 0) {
            throw new LedgerPosting("ledger has " + mismatched + " account(s) whose balance differs from entries");
        }
    }

    private List<LedgerLine> storedLines(String journalId) {
        return jdbc.query(
                "SELECT a.code, a.currency, e.direction, e.amount_minor FROM ledger_entries e "
                + "JOIN ledger_accounts a ON a.id = e.account_id WHERE e.journal_id = ? ORDER BY e.line_no",
                (rs, n) -> new LedgerLine(rs.getString(1), rs.getString(2).trim(),
                        LedgerLine.Direction.valueOf(rs.getString(3)), rs.getLong(4)),
                journalId);
    }
}
