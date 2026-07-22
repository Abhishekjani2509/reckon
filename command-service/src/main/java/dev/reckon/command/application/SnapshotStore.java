package dev.reckon.command.application;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes aggregate snapshots.
 *
 * <p>Snapshots are a pure load-time optimisation, so this store is used off the command's
 * critical path: {@link #findLatest} on load, {@link #save} after an append crosses a
 * snapshot boundary. Neither is ever required for correctness — a missing snapshot just
 * means the next load replays more events.
 */
@Repository
public class SnapshotStore {

    private static final String FIND_LATEST =
            "SELECT version, state::text FROM snapshots WHERE aggregate_id = ?";

    // Upsert the single row per aggregate, but only forward: the WHERE guard refuses to
    // overwrite a snapshot with an older version, so a slow write cannot regress the
    // snapshot under concurrency.
    private static final String SAVE = """
            INSERT INTO snapshots (aggregate_id, version, state)
            VALUES (?, ?, ?::jsonb)
            ON CONFLICT (aggregate_id)
            DO UPDATE SET version = EXCLUDED.version, state = EXCLUDED.state, created_at = now()
            WHERE snapshots.version < EXCLUDED.version
            """;

    private final JdbcTemplate jdbc;

    public SnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredSnapshot> findLatest(String aggregateId) {
        return jdbc.query(FIND_LATEST,
                (rs, n) -> new StoredSnapshot(rs.getLong("version"), rs.getString("state")),
                aggregateId).stream().findFirst();
    }

    public void save(String aggregateId, long version, String stateJson) {
        jdbc.update(SAVE, aggregateId, version, stateJson);
    }
}
