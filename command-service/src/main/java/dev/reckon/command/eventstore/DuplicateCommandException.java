package dev.reckon.command.eventstore;

/**
 * The command's idempotency key was already recorded for this aggregate — the command has
 * already been applied.
 *
 * <p>Not an error: it is the idempotency guarantee firing. It means a genuine duplicate
 * (a retry, or a concurrent identical command that committed first) reached the append.
 * The caller must respond by returning the stored result rather than applying anything.
 *
 * <p>Distinct from {@link ConcurrencyConflictException}: that is two DIFFERENT commands
 * racing for the same version and the loser must re-decide; this is the SAME command
 * arriving twice and the loser must not act at all.
 */
public class DuplicateCommandException extends RuntimeException {

    private final String aggregateId;
    private final String idempotencyKey;

    public DuplicateCommandException(String aggregateId, String idempotencyKey) {
        super("command %s already applied to %s".formatted(idempotencyKey, aggregateId));
        this.aggregateId = aggregateId;
        this.idempotencyKey = idempotencyKey;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
