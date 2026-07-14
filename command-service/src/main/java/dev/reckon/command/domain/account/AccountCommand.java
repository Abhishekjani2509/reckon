package dev.reckon.command.domain.account;

/**
 * Commands are <em>intent</em>: a request for something to happen, which the domain is
 * still free to reject. {@code Withdraw} means "I would like to withdraw" — it fails
 * against an overdraft. That is the whole distinction from an event, which is a fact
 * that already happened and cannot be argued with.
 *
 * <p>Hence the tense. Commands are imperative ({@code Deposit}), events are past tense
 * ({@code MoneyDeposited}). The naming is a discipline, not decoration: it keeps
 * "might not happen" and "definitely happened" from ever being confused in the code.
 *
 * <p>A sealed interface over records gives an exhaustive {@code switch} in the handler —
 * add a command without handling it and the compiler objects, rather than a request
 * silently falling through at runtime.
 */
public sealed interface AccountCommand {

    /** The account this command is aimed at. Also the event store's aggregate id. */
    String accountId();

    /**
     * Client-supplied de-duplication key. Delivery is at-least-once and clients retry,
     * so the same command can legitimately arrive twice; this key is what lets the
     * handler apply it exactly once.
     */
    String idempotencyKey();

    record OpenAccount(
            String accountId,
            String owner,
            String currency,
            String idempotencyKey
    ) implements AccountCommand {}

    /** {@code amountMinor} is cents, never dollars. See {@link Money}. */
    record Deposit(
            String accountId,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) implements AccountCommand {}

    /** Rejected when it would overdraw the account — the Account aggregate's invariant. */
    record Withdraw(
            String accountId,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) implements AccountCommand {}

    /**
     * Names two accounts, which is why it cannot be a simple append: no single aggregate
     * owns both sides, and Reckon has no distributed transaction to span them. It
     * resolves as a saga — debit source, credit destination, compensate if the second
     * step fails.
     */
    record Transfer(
            String accountId,        // source; the aggregate this command is routed to
            String destinationAccountId,
            long amountMinor,
            String currency,
            String idempotencyKey
    ) implements AccountCommand {}
}
