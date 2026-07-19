package dev.reckon.query.transaction;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads transaction history from the read model, newest first, with cursor pagination.
 */
@Repository
public class TransactionReadRepository {

    // Two shapes of the same query: the first page has no cursor, later pages fetch rows
    // strictly older than the cursor version. Both lean on the (account_id, version DESC)
    // index, so paging deep into history costs the same as the first page -- no OFFSET scan.
    private static final String FIRST_PAGE = """
            SELECT version, event_type, amount_minor, balance_after, currency, occurred_at
            FROM account_transactions
            WHERE account_id = ?
            ORDER BY version DESC
            LIMIT ?
            """;

    private static final String AFTER_CURSOR = """
            SELECT version, event_type, amount_minor, balance_after, currency, occurred_at
            FROM account_transactions
            WHERE account_id = ? AND version < ?
            ORDER BY version DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public TransactionReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param cursor exclusive upper bound on version; null for the first (newest) page
     * @param limit maximum rows to return
     */
    public List<TransactionResponse> page(String accountId, Long cursor, int limit) {
        if (cursor == null) {
            return jdbc.query(FIRST_PAGE, this::map, accountId, limit);
        }
        return jdbc.query(AFTER_CURSOR, this::map, accountId, cursor, limit);
    }

    private TransactionResponse map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TransactionResponse(
                rs.getLong("version"),
                rs.getString("event_type"),
                rs.getLong("amount_minor"),
                rs.getLong("balance_after"),
                rs.getString("currency"),
                rs.getTimestamp("occurred_at").toInstant());
    }
}
