package dev.reckon.projection.projection;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes to the account_balances read model. Every method is idempotent, because the
 * event stream is delivered at-least-once and any event may arrive more than once.
 *
 * <p>Each write returns the resulting {@link BalanceSnapshot} <em>only when it actually
 * applied</em> (via {@code RETURNING}), and empty when the idempotency guard rejected a
 * duplicate. That presence-or-absence is how the projector knows whether to also record a
 * transaction row and refresh Redis — an already-applied event does none of that again.
 */
@Repository
public class AccountBalanceRepository {

    // ON CONFLICT DO NOTHING makes an account-opened replay a no-op. RETURNING yields the
    // new row when inserted, and nothing on conflict -- so an empty result means duplicate.
    private static final String INSERT_OPENED = """
            INSERT INTO account_balances (account_id, owner, currency, balance_minor, last_version)
            VALUES (?, ?, ?, 0, 1)
            ON CONFLICT (account_id) DO NOTHING
            RETURNING account_id, balance_minor, currency, last_version
            """;

    // The idempotency guard. The WHERE clause admits this event only when the row is at
    // exactly the previous version, so:
    //   - a re-delivered event (its version already applied) matches 0 rows -> no change
    //   - an event that arrives before its predecessor matches 0 rows -> it waits
    //   - the genuine next event matches 1 row -> applied once
    // De-duplication and ordering from one atomic statement, no read-modify-write.
    // RETURNING hands back the post-update balance so the caller need not re-read it.
    private static final String APPLY_DELTA = """
            UPDATE account_balances
            SET balance_minor = balance_minor + ?, last_version = ?, updated_at = now()
            WHERE account_id = ? AND last_version = ?
            RETURNING account_id, balance_minor, currency, last_version
            """;

    private final JdbcTemplate jdbc;

    public AccountBalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the opened account's snapshot, or empty if it was already opened */
    public Optional<BalanceSnapshot> insertOpened(String accountId, String owner, String currency) {
        return jdbc.query(INSERT_OPENED, this::mapSnapshot, accountId, owner, currency).stream().findFirst();
    }

    /**
     * Applies a balance change for {@code version}, but only if the row currently sits at
     * {@code version - 1}.
     *
     * @param deltaMinor signed change: positive for a credit, negative for a debit
     * @return the post-change snapshot, or empty if the guard rejected it (duplicate or gap)
     */
    public Optional<BalanceSnapshot> applyDelta(String accountId, long deltaMinor, long version) {
        return jdbc.query(APPLY_DELTA, this::mapSnapshot, deltaMinor, version, accountId, version - 1)
                .stream().findFirst();
    }

    private BalanceSnapshot mapSnapshot(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new BalanceSnapshot(
                rs.getString("account_id"),
                rs.getLong("balance_minor"),
                rs.getString("currency"),
                rs.getLong("last_version"));
    }
}
