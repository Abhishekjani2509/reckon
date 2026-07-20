package dev.reckon.command.domain.account;

import java.util.List;

/**
 * The Account aggregate: the consistency boundary for one account's invariant, which is
 * that it may never go negative.
 *
 * <p><b>This object is not stored anywhere.</b> It is a fold over the account's event
 * history — rebuilt from the log on every command, used to make one decision, and thrown
 * away. The events are the durable truth; this is a derived, momentary view of them.
 *
 * <p>The class has two halves, and the split is the entire mental model:
 *
 * <ul>
 *   <li>{@link #decide} takes a command (intent) and returns events (facts), or throws.
 *       Invariants live here. It is allowed to say no.</li>
 *   <li>{@link #apply} takes an event and updates state. <b>It can never say no.</b>
 *       The event already happened; refusing it would be refusing reality.</li>
 * </ul>
 *
 * <p>That asymmetry is what makes replay safe. Rehydrating never re-runs validation — a
 * withdrawal that was legitimate against last year's balance is not re-litigated against
 * today's. Validation happens once, at decide time. Afterwards it is only arithmetic.
 * Put a rule inside {@code apply} and your own history stops loading.
 */
public final class Account {

    private final String accountId;

    private boolean opened;
    private String owner;
    private String currency;
    private long balanceMinor;
    private long version;

    private Account(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Rebuilds current state by folding history in version order. An account with no
     * events folds to "not opened", which is how a request for an account that never
     * existed is distinguished from one that does.
     *
     * @param history the account's events, ordered by version ascending
     */
    public static Account rehydrate(String accountId, List<AccountEvent> history) {
        Account account = new Account(accountId);
        for (AccountEvent event : history) {
            account.apply(event);
        }
        return account;
    }

    /**
     * Decides what happened, given what was asked and what is already true.
     *
     * <p>Pure: it reads state and returns events without writing anything. Persisting is
     * the caller's job, which is what lets a conflicting append be retried by simply
     * calling this again against freshly-loaded state.
     *
     * @return the events to append, in order
     */
    public List<AccountEvent> decide(AccountCommand command) {
        return switch (command) {
            case AccountCommand.OpenAccount c -> decideOpen(c);
            case AccountCommand.Deposit c -> decideDeposit(c);
            case AccountCommand.Withdraw c -> decideWithdraw(c);

            // A transfer spans two aggregates, so no single Account can decide it in one
            // shot. It is composed from the step decisions below (debit, credit, complete,
            // compensate) by TransferSaga. Reaching here means a transfer was mis-routed
            // through the single-aggregate path.
            case AccountCommand.Transfer c -> throw new UnsupportedOperationException(
                    "Transfer must run through TransferSaga, not the single-aggregate command path");
        };
    }

    // --- Transfer saga steps ---------------------------------------------------
    //
    // Each method decides ONE aggregate's part of a transfer. The saga calls them across
    // the source and destination accounts and, on failure, composes the compensation. They
    // are ordinary decisions — pure, validating, returning events — so each runs through
    // the same optimistic-concurrency append as any other command.

    /**
     * Source side, step one: begin the transfer and debit. Returned atomically so the
     * initiation marker and the debit share a version bump — the source can never be
     * debited without a recorded intent, or marked as initiating without the debit.
     *
     * <p>This is where a transfer is rejected before any money moves: an underfunded
     * source throws here, and the saga never touches the destination.
     */
    public List<AccountEvent> decideTransferDebit(
            String transferId, String destinationAccountId, long amountMinor, String currency, String idempotencyKey) {
        requireOpen();
        if (accountId.equals(destinationAccountId)) {
            throw new AccountExceptions.SelfTransferNotAllowed(accountId);
        }
        Money.requirePositive(amountMinor);
        requireSameCurrency(currency);
        if (balanceMinor < amountMinor) {
            throw new AccountExceptions.InsufficientFunds(accountId, balanceMinor, amountMinor);
        }
        return List.of(
                new AccountEvent.TransferInitiated(
                        accountId, transferId, destinationAccountId, amountMinor, currency, idempotencyKey),
                new AccountEvent.MoneyDebited(accountId, transferId, amountMinor, currency, idempotencyKey));
    }

    /**
     * Destination side: credit the incoming funds. Fails if the destination does not exist
     * or holds a different currency — and that failure, occurring after the source is
     * already debited, is exactly what drives compensation.
     */
    public List<AccountEvent> decideTransferCredit(
            String transferId, long amountMinor, String currency, String idempotencyKey) {
        requireOpen();
        requireSameCurrency(currency);
        return List.of(new AccountEvent.MoneyCredited(accountId, transferId, amountMinor, currency, idempotencyKey));
    }

    /** Source side, success marker. Balance-neutral; records that the transfer completed. */
    public List<AccountEvent> decideTransferComplete(String transferId, String idempotencyKey) {
        requireOpen();
        return List.of(new AccountEvent.TransferCompleted(accountId, transferId, idempotencyKey));
    }

    /**
     * Source side, compensation: put the debited funds back after a failed credit. A
     * forward event that reverses an earlier one — the log is never rewritten, so undo is
     * a new fact, not a deletion. No overdraft check: crediting only increases the balance.
     */
    public List<AccountEvent> decideTransferCompensate(
            String transferId, long amountMinor, String currency, String reason, String idempotencyKey) {
        requireOpen();
        return List.of(new AccountEvent.TransferCompensated(
                accountId, transferId, amountMinor, currency, reason, idempotencyKey));
    }

    /**
     * Folds committed events into this instance, for a caller that has just appended
     * them and wants the resulting state.
     *
     * <p>Only ever pass events the store has accepted. Applying an event that was not
     * durably appended produces an aggregate that disagrees with the log — and the log
     * is what is true.
     */
    public void applyAll(List<AccountEvent> committed) {
        committed.forEach(this::apply);
    }

    private List<AccountEvent> decideOpen(AccountCommand.OpenAccount command) {
        if (opened) {
            throw new AccountExceptions.AccountAlreadyExists(accountId);
        }
        return List.of(new AccountEvent.AccountOpened(
                accountId, command.owner(), command.currency(), command.idempotencyKey()));
    }

    private List<AccountEvent> decideDeposit(AccountCommand.Deposit command) {
        requireOpen();
        Money.requirePositive(command.amountMinor());
        requireSameCurrency(command.currency());
        return List.of(new AccountEvent.MoneyDeposited(
                accountId, command.amountMinor(), command.currency(), command.idempotencyKey()));
    }

    private List<AccountEvent> decideWithdraw(AccountCommand.Withdraw command) {
        requireOpen();
        Money.requirePositive(command.amountMinor());
        requireSameCurrency(command.currency());

        // The invariant. Checked against the balance folded from history a moment ago —
        // which is exactly why a concurrent write that lands first must force a reload
        // and a fresh decision rather than a bare retry of the append.
        if (balanceMinor < command.amountMinor()) {
            throw new AccountExceptions.InsufficientFunds(accountId, balanceMinor, command.amountMinor());
        }
        return List.of(new AccountEvent.MoneyWithdrawn(
                accountId, command.amountMinor(), command.currency(), command.idempotencyKey()));
    }

    private void requireOpen() {
        if (!opened) {
            throw new AccountExceptions.AccountNotFound(accountId);
        }
    }

    private void requireSameCurrency(String commandCurrency) {
        if (!currency.equals(commandCurrency)) {
            throw new AccountExceptions.CurrencyMismatch(accountId, currency, commandCurrency);
        }
    }

    /**
     * Applies one fact. Total by construction: every branch updates state, none rejects.
     *
     * <p>The switch is exhaustive over the sealed {@link AccountEvent} hierarchy, so a
     * new event type that nobody teaches the fold about is a compile error rather than a
     * balance that is quietly wrong.
     */
    private void apply(AccountEvent event) {
        switch (event) {
            case AccountEvent.AccountOpened e -> {
                this.opened = true;
                this.owner = e.owner();
                this.currency = e.currency();
                this.balanceMinor = 0;
            }
            case AccountEvent.MoneyDeposited e -> this.balanceMinor += e.amountMinor();
            case AccountEvent.MoneyWithdrawn e -> this.balanceMinor -= e.amountMinor();

            // Transfer events: the money moves on debit and credit. Initiated and
            // Completed are saga bookkeeping and leave the balance alone.
            case AccountEvent.TransferInitiated ignored -> { }
            case AccountEvent.MoneyDebited e -> this.balanceMinor -= e.amountMinor();
            case AccountEvent.MoneyCredited e -> this.balanceMinor += e.amountMinor();
            case AccountEvent.TransferCompleted ignored -> { }
            case AccountEvent.TransferCompensated e -> this.balanceMinor += e.amountMinor();
        }

        // Versions are contiguous from 1, so the count of applied events is the current
        // version. The event store guarantees that contiguity via its unique constraint.
        this.version++;
    }

    public String accountId() {
        return accountId;
    }

    public boolean isOpened() {
        return opened;
    }

    public String owner() {
        return owner;
    }

    public String currency() {
        return currency;
    }

    public long balanceMinor() {
        return balanceMinor;
    }

    /** The version this instance was folded to — the expected version for the next append. */
    public long version() {
        return version;
    }
}
