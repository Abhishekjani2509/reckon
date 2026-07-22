package dev.reckon.query.balance;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads balances from the Postgres read model — the durable fallback when Redis misses,
 * and the ground truth the hot path is a copy of.
 */
@Repository
public class BalanceReadRepository {

    private static final String SELECT_BALANCE = """
            SELECT account_id, balance_minor, currency, last_version
            FROM account_balances
            WHERE account_id = ?
            """;

    private static final String LIST_ACCOUNTS = """
            SELECT account_id, owner, currency, balance_minor, last_version
            FROM account_balances
            ORDER BY owner, account_id
            """;

    private final JdbcTemplate jdbc;

    public BalanceReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BalanceResponse> find(String accountId) {
        return jdbc.query(SELECT_BALANCE,
                (rs, n) -> new BalanceResponse(
                        rs.getString("account_id"),
                        rs.getLong("balance_minor"),
                        rs.getString("currency"),
                        rs.getLong("last_version")),
                accountId).stream().findFirst();
    }

    public List<AccountSummary> findAll() {
        return jdbc.query(LIST_ACCOUNTS,
                (rs, n) -> new AccountSummary(
                        rs.getString("account_id"),
                        rs.getString("owner"),
                        rs.getString("currency"),
                        rs.getLong("balance_minor"),
                        rs.getLong("last_version")));
    }
}
