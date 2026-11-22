package com.payments.gateway.ledger;

/** Chart of accounts used by the payment engine. */
public final class LedgerAccounts {

    /** Amount the gateway owes us for captured payments. */
    public static final String GATEWAY_RECEIVABLE = "GATEWAY_RECEIVABLE";

    /** Amount owed to the merchant for captured payments. */
    public static final String MERCHANT_PAYABLE = "MERCHANT_PAYABLE";

    private LedgerAccounts() {
    }
}
