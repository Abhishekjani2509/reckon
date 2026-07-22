package dev.reckon.command.eventstore;

import dev.reckon.command.outbox.OutboxWriter;
import java.sql.Timestamp;
import java.time.Instant;
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

    // occurred_at is set explicitly rather than left to the column default, so the exact
    // instant written to the event row is the instant carried in the outbox envelope.
    private static final String INSERT_EVENT = """
            INSERT INTO events (event_id, aggregate_id, aggregate_type, version, event_type, payload, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
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

    private static final String LOAD_STREAM_AFTER = """
            SELECT sequence_number, event_id, aggregate_id, aggregate_type,
                   version, event_type, payload::text AS payload, occurred_at
            FROM events
            WHERE aggregate_id = ? AND version > ?
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
    private final OutboxWriter outboxWriter;

    public JdbcEventStore(JdbcTemplate jdbc, OutboxWriter outboxWriter) {
        this.jdbc = jdbc;
        this.outboxWriter = outboxWriter;
    }

    @Override
    public List<StoredEvent> loadStream(String aggregateId) {
        return jdbc.query(LOAD_STREAM, ROW_MAPPER, aggregateId);
    }

    @Override
    public List<StoredEvent> loadStreamAfter(String aggregateId, long afterVersion) {
        return jdbc.query(LOAD_STREAM_AFTER, ROW_MAPPER, aggregateId, afterVersion);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Transactional so that a command's events are all-or-nothing. The outbox rows are
     * written here too, in this same transaction — that shared atomicity is what lets the
     * event and its publication intent commit as one, dissolving the dual-write problem.
     *
     * <p>Note the retry must live <em>outside</em> this method: once Postgres raises the
     * constraint violation the transaction is aborted and will accept no further
     * statements, so retrying within it cannot work. Each attempt needs a fresh
     * transaction, which is what calling this again gives.
     */
    @Override
    @Transactional
    public void append(String aggregateId, String aggregateType, long expectedVersion, List<NewEvent> events) {
        append(aggregateId, aggregateType, expectedVersion, events, null);
    }

    private static final String INSERT_PROCESSED = """
            INSERT INTO processed_commands (aggregate_id, idempotency_key, result_json)
            VALUES (?, ?, ?::jsonb)
            """;

    @Override
    @Transactional
    public void append(String aggregateId, String aggregateType, long expectedVersion,
                       List<NewEvent> events, ProcessedCommand idempotency) {

        // The dedup row goes first. If this command was already applied, the primary key
        // rejects it here, before a single event is written — so a duplicate never even
        // reaches the event log, and its violation cannot be confused with the version
        // conflict handled below. Both statements share this transaction, so if the events
        // then fail on a version conflict, this row rolls back with them.
        if (idempotency != null) {
            try {
                jdbc.update(INSERT_PROCESSED,
                        aggregateId, idempotency.idempotencyKey(), idempotency.resultJson());
            } catch (DuplicateKeyException e) {
                throw new DuplicateCommandException(aggregateId, idempotency.idempotencyKey());
            }
        }

        long version = expectedVersion;
        try {
            for (NewEvent event : events) {
                version++;
                UUID eventId = UUID.randomUUID();
                Instant occurredAt = Instant.now();
                jdbc.update(INSERT_EVENT,
                        eventId,
                        aggregateId,
                        aggregateType,
                        version,
                        event.eventType(),
                        event.payloadJson(),
                        Timestamp.from(occurredAt));
                outboxWriter.enqueue(eventId, aggregateId, aggregateType,
                        version, event.eventType(), event.payloadJson(), occurredAt);
            }
        } catch (DuplicateKeyException e) {
            // The events table has two unique constraints: (aggregate_id, version) and
            // event_id. event_id is a fresh random UUID per insert, so a collision there is
            // not a thing that happens — a duplicate key here means someone else took the
            // version we were claiming.
            throw new ConcurrencyConflictException(aggregateId, expectedVersion, e);
        }
    }
}
