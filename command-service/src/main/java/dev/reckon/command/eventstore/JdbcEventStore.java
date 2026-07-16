package dev.reckon.command.eventstore;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The event store on PostgreSQL. Hand-written SQL, deliberately: this is where the
 * system's correctness argument lives, and it should be readable rather than generated.
 */
@Repository
public class JdbcEventStore implements EventStore {

    private static final String INSERT_EVENT = """
            INSERT INTO events (event_id, aggregate_id, aggregate_type, version, event_type, payload)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """;

    // payload::text because the JDBC driver hands back JSONB as a PGobject; casting in
    // SQL keeps the mapper dealing in plain strings.
    private static final String LOAD_STREAM = """
            SELECT sequence_number, event_id, aggregate_id, aggregate_type,
                   version, event_type, payload::text AS payload, occurred_at
            FROM events
            WHERE aggregate_id = ?
            ORDER BY version
            """;

    private static final RowMapper<StoredEvent> ROW_MAPPER = (rs, rowNum) -> new StoredEvent(
            rs.getLong("sequence_number"),
            rs.getObject("event_id", UUID.class),
            rs.getString("aggregate_id"),
            rs.getString("aggregate_type"),
            rs.getLong("version"),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoredEvent> loadStream(String aggregateId) {
        return jdbc.query(LOAD_STREAM, ROW_MAPPER, aggregateId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Transactional so that a command's events are all-or-nothing. Note the retry must
     * live <em>outside</em> this method: once Postgres raises the constraint violation the
     * transaction is aborted and will accept no further statements, so retrying within it
     * cannot work. Each attempt needs a fresh transaction, which is what calling this
     * again gives.
     */
    @Override
    @Transactional
    public void append(String aggregateId, String aggregateType, long expectedVersion, List<NewEvent> events) {
        long version = expectedVersion;
        try {
            for (NewEvent event : events) {
                version++;
                jdbc.update(INSERT_EVENT,
                        UUID.randomUUID(),
                        aggregateId,
                        aggregateType,
                        version,
                        event.eventType(),
                        event.payloadJson());
            }
        } catch (DuplicateKeyException e) {
            // The table has two unique constraints: (aggregate_id, version) and event_id.
            // event_id is a fresh random UUID per insert, so a collision there is not a
            // thing that happens — a duplicate key here means someone else took the
            // version we were claiming.
            throw new ConcurrencyConflictException(aggregateId, expectedVersion, e);
        }
    }
}
