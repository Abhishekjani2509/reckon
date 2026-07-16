package dev.reckon.command.domain.account;

/**
 * Failures the domain raises when a command cannot be honoured.
 *
 * <p>These are all thrown from {@link Account#decide}, never from {@code apply} — a
 * command may be refused, a fact that already happened may not.
 */
public final class AccountExceptions {

    private AccountExceptions() {}

    /** The aggregate has no {@code AccountOpened} event, so as far as history knows it does not exist. */
    public static final class AccountNotFound extends RuntimeException {
        public AccountNotFound(String accountId) {
            super("account not found: " + accountId);
        }
    }

    public static final class AccountAlreadyExists extends RuntimeException {
        public AccountAlreadyExists(String accountId) {
            super("account already exists: " + accountId);
        }
    }

    /** The no-overdraft invariant. The one rule that makes this a ledger rather than a log. */
    public static final class InsufficientFunds extends RuntimeException {
        public InsufficientFunds(String accountId, long balanceMinor, long requestedMinor) {
            super("insufficient funds in %s: balance %d, requested %d"
                    .formatted(accountId, balanceMinor, requestedMinor));
        }
    }

    /**
     * An account holds exactly one currency. Mixing them would make the balance a
     * meaningless sum of unlike units — cross-currency movement has to be an explicit
     * conversion, not an accident of arithmetic.
     */
    public static final class CurrencyMismatch extends RuntimeException {
        public CurrencyMismatch(String accountId, String accountCurrency, String commandCurrency) {
            super("account %s is denominated in %s, command used %s"
                    .formatted(accountId, accountCurrency, commandCurrency));
        }
    }
}
