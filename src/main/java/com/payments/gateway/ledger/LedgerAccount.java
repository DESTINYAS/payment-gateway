package com.payments.gateway.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Ledger account. Balances are only changed while the row is locked with
 * {@code SELECT ... FOR UPDATE} (see {@link LedgerAccountRepository}). {@code @Version} is a
 * second line of defence against lost updates.
 */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Version
    private long version;

    protected LedgerAccount() {
    }

    public LedgerAccount(String code, String currency) {
        this.code = code;
        this.currency = currency;
    }

    /** Credits increase the balance, debits decrease it. */
    public void apply(LedgerLine.Direction direction, long amountMinor) {
        balanceMinor += direction == LedgerLine.Direction.CREDIT ? amountMinor : -amountMinor;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }
}
