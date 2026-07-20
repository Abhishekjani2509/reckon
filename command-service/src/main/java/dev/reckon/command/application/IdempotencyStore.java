package dev.reckon.command.application;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and finalises idempotency records.
 *
 * <p>Note it does not <em>insert</em> the record — that happens inside the event store's
 * append, in the same transaction as the events, so the two are atomic. This store only
 * reads a prior result (the fast path and the post-conflict lookup) and finalises a
 * transfer's placeholder result once the saga reaches a terminal step.
 */
@Repository
public class IdempotencyStore {

    private static final String FIND =
            "SELECT result_json::text FROM processed_commands WHERE aggregate_id = ? AND idempotency_key = ?";

    private static final String FINALISE =
            "UPDATE processed_commands SET result_json = ?::jsonb WHERE aggregate_id = ? AND idempotency_key = ?";

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The stored result JSON for a previously-applied command, or empty if none. */
    public Optional<String> find(String aggregateId, String idempotencyKey) {
        return jdbc.query(FIND, (rs, n) -> rs.getString(1), aggregateId, idempotencyKey)
                .stream().findFirst();
    }

    /**
     * Replaces the stored result — used to turn a transfer's PENDING placeholder into its
     * terminal outcome once the saga completes or compensates. The dedup row already
     * exists (written with the debit), so this only updates its result.
     */
    public void finalise(String aggregateId, String idempotencyKey, String resultJson) {
        jdbc.update(FINALISE, resultJson, aggregateId, idempotencyKey);
    }
}
