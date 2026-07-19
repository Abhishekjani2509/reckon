package dev.reckon.projection.projection;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes the account_transactions read model — one row per event, the transaction history.
 */
@Repository
public class AccountTransactionRepository {

    // ON CONFLICT DO NOTHING on the (account_id, version) primary key: a re-delivered event
    // does not create a second history row. The projector only calls this for events that
    // genuinely applied to the balance, so the two read models stay in step.
    private static final String INSERT_TRANSACTION = """
            INSERT INTO account_transactions
                (account_id, version, event_type, amount_minor, balance_after, currency, occurred_at, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_id, version) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public AccountTransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String accountId, long version, String eventType, long signedAmountMinor,
                       long balanceAfter, String currency, Instant occurredAt, String idempotencyKey) {
        jdbc.update(INSERT_TRANSACTION,
                accountId, version, eventType, signedAmountMinor, balanceAfter, currency,
                Timestamp.from(occurredAt), idempotencyKey);
    }
}
