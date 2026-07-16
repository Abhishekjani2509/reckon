package dev.reckon.command.eventstore;

/**
 * Another writer appended to this aggregate first, so the version being claimed is gone.
 *
 * <p>Not a failure — it is the concurrency control doing its job, and the expected
 * outcome of a race that the log correctly refused to let both sides win.
 *
 * <p>The caller must reload, fold, and <b>decide again</b>. Re-appending the same events
 * against a new version would append facts the domain never authorised against the state
 * that now exists — which is precisely the double-spend the constraint just prevented.
 */
public class ConcurrencyConflictException extends RuntimeException {

    private final String aggregateId;
    private final long expectedVersion;

    public ConcurrencyConflictException(String aggregateId, long expectedVersion, Throwable cause) {
        super("concurrent write to %s: version %d was already taken"
                .formatted(aggregateId, expectedVersion + 1), cause);
        this.aggregateId = aggregateId;
        this.expectedVersion = expectedVersion;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
