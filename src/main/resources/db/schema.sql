-- Schema for the payment gateway. Applied on startup (spring.sql.init) and idempotent.
-- Every money column is a BIGINT in minor units; amounts are strictly positive.

-- Payments: one row per merchant reference. `reference` is the business-level uniqueness guarantee.
CREATE TABLE IF NOT EXISTS payments (
    id                BIGSERIAL PRIMARY KEY,
    reference         VARCHAR(64)  NOT NULL,
    amount_minor      BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency          CHAR(3)      NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    gateway_reference VARCHAR(128),
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_reference UNIQUE (reference)
);

-- Idempotency keys. Written in the same transaction as the payment, so a rollback removes both.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idem_key              VARCHAR(128) PRIMARY KEY,
    fingerprint           VARCHAR(64)  NOT NULL,
    reference             VARCHAR(64),
    response_status       VARCHAR(16),
    gateway_reference     VARCHAR(128),
    pending_reconciliation BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Ledger accounts, one per (code, currency). Balance is credits minus debits, in minor units.
CREATE TABLE IF NOT EXISTS ledger_accounts (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(64) NOT NULL,
    currency      CHAR(3)     NOT NULL,
    balance_minor BIGINT      NOT NULL DEFAULT 0,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_ledger_accounts UNIQUE (code, currency)
);

-- One row per journal. The primary key stops the same journal being posted twice.
CREATE TABLE IF NOT EXISTS ledger_journals (
    journal_id VARCHAR(128) PRIMARY KEY,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id           BIGSERIAL PRIMARY KEY,
    journal_id   VARCHAR(128) NOT NULL REFERENCES ledger_journals (journal_id),
    line_no      INT          NOT NULL,
    account_id   BIGINT       NOT NULL REFERENCES ledger_accounts (id),
    direction    VARCHAR(6)   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor BIGINT       NOT NULL CHECK (amount_minor > 0),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_entries_line UNIQUE (journal_id, line_no)
);

-- Webhook deliveries. The event_id primary key is the dedupe guard: a redelivered event
-- inserts zero rows and is reported as DUPLICATE without touching balances.
CREATE TABLE IF NOT EXISTS webhook_events (
    event_id   VARCHAR(128) PRIMARY KEY,
    reference  VARCHAR(64)  NOT NULL,
    event_type VARCHAR(64)  NOT NULL,
    outcome    VARCHAR(64)  NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
