package dev.reckon.command.eventstore;

import java.util.List;

/**
 * Append-only access to the event log.
 *
 * <p>Deliberately ignorant of accounts, balances, and every other domain concept: it
 * moves opaque {@code (eventType, payloadJson)} pairs in and out, and the domain owns
 * their meaning. Serialisation lives one layer up, so the store stays reusable for any
 * aggregate.
 *
 * <p>There is no {@code update} or {@code delete}, and no amount of API design would
 * add one — the database rejects both outright.
 */
public interface EventStore {

    /**
     * Every event for one aggregate, ordered by version ascending — the input to a fold.
     * Returns empty for an aggregate that has never existed.
     */
    List<StoredEvent> loadStream(String aggregateId);

    /**
     * Events for one aggregate with version strictly greater than {@code afterVersion},
     * ordered ascending. This is the snapshot-aware load path: replay only what happened
     * after the snapshot, not the whole history.
     */
    List<StoredEvent> loadStreamAfter(String aggregateId, long afterVersion);

    /**
     * Appends events as versions {@code expectedVersion + 1, +2, ...}, atomically.
     *
     * <p>{@code expectedVersion} is the version the caller folded its decision from. If
     * another writer has appended since, the version the caller is claiming is taken and
     * the unique constraint on {@code (aggregate_id, version)} rejects the write — this
     * throws {@link ConcurrencyConflictException} and nothing is persisted.
     *
     * <p>All events land or none do: a command's facts must never be half-recorded, or
     * the log would describe a state the domain never authorised.
     *
     * @throws ConcurrencyConflictException if another writer claimed the version first
     */
    void append(String aggregateId, String aggregateType, long expectedVersion, List<NewEvent> events);

    /**
     * As {@link #append}, but additionally records an idempotency row in the same
     * transaction as the events.
     *
     * <p>The dedup row is written first, so a duplicate command is rejected before any
     * event is persisted, and a duplicate-key violation there is distinguishable from a
     * version conflict on the events. Because it shares the transaction, the events and the
     * idempotency record commit together or roll back together.
     *
     * @throws DuplicateCommandException if this idempotency key was already recorded for
     *     the aggregate — the command is a duplicate and must not be applied again
     * @throws ConcurrencyConflictException if another writer claimed the version first
     */
    void append(String aggregateId, String aggregateType, long expectedVersion,
                List<NewEvent> events, ProcessedCommand idempotency);
}
