package dev.reckon.command.domain.account;

/**
 * Events are facts that already happened. They are immutable, past tense, and never
 * deleted or edited — the database enforces that last part with a trigger (see
 * {@code V1__create_events_table.sql}).
 *
 * <p>The consequence is worth internalising: <b>there is no undo.</b> A wrong event is
 * not corrected, it is <em>compensated</em> — a new event is appended that reverses its
 * effect, and both stay in the log forever. That is why {@link TransferCompensated}
 * exists rather than a delete, and it is how ledgers have always worked: an accountant
 * voids a bad entry with a balancing entry, not an eraser.
 *
 * <p>These records are the in-memory shape. On the way to Postgres they are serialised
 * into the {@code payload} JSONB column and wrapped in an envelope carrying the
 * aggregate id, version, and type.
 *
 * <p><b>Contract note.</b> These types are shared with consumers on the read side. Once
 * an event is in the store its shape is permanent — removing a field makes historical
 * events unreadable, and unreadable history means the read model can no longer be
 * rebuilt. Add fields; never repurpose or remove them.
 */
public sealed interface AccountEvent {

    String accountId();

    /**
     * Every event carries the key that caused it, which makes de-duplication possible by
     * replay alone: to know whether a command was already applied, ask the log — there
     * is no side table to keep in sync.
     */
    String idempotencyKey();

    record AccountOpened(
            String accountId,
            String owner,
            String currency,
            String idempotencyKey
    ) implements AccountEvent {}

    record MoneyDeposited(
            String accountId,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) implements AccountEvent {}

    record MoneyWithdrawn(
            String accountId,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) implements AccountEvent {}

    // --- Transfer saga ---------------------------------------------------------
    //
    // A transfer is not one event but a sequence, because it spans two aggregates and
    // there is no two-phase commit. Each step is independently durable, so a crash
    // anywhere leaves a log that says exactly how far it got — which is what makes
    // recovery and compensation possible at all.
    //
    // transferId ties the steps together: it is the thread the saga follows to find its
    // own state after a restart.

    record TransferInitiated(
            String accountId,        // source
            String transferId,
            String destinationAccountId,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) implements AccountEvent {}

    record MoneyDebited(
            String accountId,        // source
            String transferId,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) implements AccountEvent {}

    record MoneyCredited(
            String accountId,        // destination
            String transferId,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) implements AccountEvent {}

    record TransferCompleted(
            String accountId,        // source
            String transferId,
            String idempotencyKey
    ) implements AccountEvent {}

    /** The debit happened but the credit could not. Puts the money back on the source. */
    record TransferCompensated(
            String accountId,        // source
            String transferId,
            long amountMinor,
            String currency,
            String reason,
            String idempotencyKey
    ) implements AccountEvent {}
}
