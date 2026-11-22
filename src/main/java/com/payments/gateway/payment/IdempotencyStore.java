package com.payments.gateway.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Idempotency keys. {@link #claim} is an atomic {@code INSERT ... ON CONFLICT DO NOTHING}.
 *
 * <p>Under PostgreSQL, a second concurrent insert of the same key blocks until the first
 * transaction commits or rolls back. It then sees either the committed record (replay) or nothing
 * (rollback, so it may claim). Because the claim and the payment write share one transaction,
 * a visible key is always a completed one.
 */
@Repository
public class IdempotencyStore {

    /** Stored outcome of an earlier request with the same key. */
    public record Stored(String fingerprint, PaymentResult result) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this caller now owns the key, false if it already exists */
    public boolean claim(String key, String fingerprint) {
        return jdbc.update(
                "INSERT INTO idempotency_keys (idem_key, fingerprint) VALUES (?, ?) "
                + "ON CONFLICT (idem_key) DO NOTHING", key, fingerprint) == 1;
    }

    public void complete(String key, PaymentResult result) {
        jdbc.update("UPDATE idempotency_keys SET reference = ?, response_status = ?, "
                + "gateway_reference = ?, pending_reconciliation = ? WHERE idem_key = ?",
                result.reference(), result.status().name(), result.gatewayReference(),
                result.pendingReconciliation(), key);
    }

    public Optional<Stored> find(String key) {
        List<Stored> rows = jdbc.query(
                "SELECT fingerprint, reference, response_status, gateway_reference, pending_reconciliation "
                + "FROM idempotency_keys WHERE idem_key = ?",
                (rs, n) -> new Stored(rs.getString("fingerprint"), new PaymentResult(
                        rs.getString("reference"),
                        rs.getString("response_status") == null ? null
                                : PaymentStatus.valueOf(rs.getString("response_status")),
                        rs.getString("gateway_reference"),
                        rs.getBoolean("pending_reconciliation"))),
                key);
        return rows.stream().findFirst();
    }
}
