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

            // A transfer spans two aggregates, so no single Account can decide it — it
            // resolves as a saga rather than an append. Failing loudly beats a silent
            // no-op the caller would mistake for success.
            case AccountCommand.Transfer c -> throw new UnsupportedOperationException(
                    "Transfer is not implemented: it spans two aggregates and must run as a saga");
        };
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
