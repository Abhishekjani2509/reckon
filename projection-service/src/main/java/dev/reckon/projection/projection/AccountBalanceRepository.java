package dev.reckon.projection.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes to the account_balances read model. Every method is idempotent, because the
 * event stream is delivered at-least-once and any event may arrive more than once.
 */
@Repository
public class AccountBalanceRepository {

    // ON CONFLICT DO NOTHING makes an account-opened replay a no-op: the row already
    // exists, so a duplicate AccountOpened neither errors nor resets the balance.
    private static final String INSERT_OPENED = """
            INSERT INTO account_balances (account_id, owner, currency, balance_minor, last_version)
            VALUES (?, ?, ?, 0, 1)
            ON CONFLICT (account_id) DO NOTHING
            """;

    // The idempotency guard. The WHERE clause admits this event only when the row is at
    // exactly the previous version, so:
    //   - a re-delivered event (its version already applied) matches 0 rows -> no change
    //   - an event that arrives before its predecessor matches 0 rows -> it waits
    //   - the genuine next event matches 1 row -> applied once
    // De-duplication and ordering from one atomic statement, no read-modify-write.
    private static final String APPLY_DELTA = """
            UPDATE account_balances
            SET balance_minor = balance_minor + ?, last_version = ?, updated_at = now()
            WHERE account_id = ? AND last_version = ?
            """;

    private final JdbcTemplate jdbc;

    public AccountBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if a new row was created, false if the account was already opened */
    public boolean insertOpened(String accountId, String owner, String currency) {
        return jdbc.update(INSERT_OPENED, accountId, owner, currency) == 1;
    }

    /**
     * Applies a balance change for {@code version}, but only if the row currently sits at
     * {@code version - 1}.
     *
     * @param deltaMinor signed change: positive for a credit, negative for a debit
     * @return true if applied, false if the guard rejected it (duplicate or not-yet-applicable)
     */
    public boolean applyDelta(String accountId, long deltaMinor, long version) {
        return jdbc.update(APPLY_DELTA, deltaMinor, version, accountId, version - 1) == 1;
    }
}
