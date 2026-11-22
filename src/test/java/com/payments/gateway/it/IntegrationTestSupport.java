package com.payments.gateway.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared base for integration tests: one PostgreSQL 16 container per JVM, the real schema, and
 * a scripted gateway. Each test starts from empty tables.
 */
@SpringBootTest
@Import(IntegrationTestSupport.Config.class)
public abstract class IntegrationTestSupport {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ScriptedGatewayClient gateway;

    @Autowired
    protected com.payments.gateway.webhook.WebhookSignatureVerifier verifier;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("payments.gateway.initial-backoff-ms", () -> "0");
        registry.add("payments.gateway.max-backoff-ms", () -> "0");
    }

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE webhook_events, ledger_entries, ledger_journals, ledger_accounts, "
                + "idempotency_keys, payments RESTART IDENTITY");
        gateway.reset();
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /** Signed webhook delivery, using the same verifier the service uses, at the current time. */
    protected String[] signedWebhook(String eventId, String type, String reference, long amountMinor) {
        String body = "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"reference\":\""
                + reference + "\",\"gatewayReference\":\"gw-1\",\"amountMinor\":" + amountMinor
                + ",\"currency\":\"NGN\"}";
        String header = verifier.sign(body, java.time.Instant.now().getEpochSecond());
        return new String[] {body, header};
    }

    /** Balance of a ledger account, credits minus debits, or 0 if the account does not exist. */
    protected long balance(String code) {
        Long b = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance_minor), 0) FROM ledger_accounts WHERE code = ?", Long.class, code);
        return b == null ? 0 : b;
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        ScriptedGatewayClient scriptedGatewayClient() {
            return new ScriptedGatewayClient();
        }
    }
}
