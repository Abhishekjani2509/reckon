package dev.reckon.command.domain.account;

/**
 * Money-handling rules for Reckon, in one place so they're hard to drift from.
 *
 * <p><b>All amounts are integer minor units.</b> $50.00 is {@code 5000L}, not
 * {@code 50.0}. Never {@code float}, never {@code double}, anywhere in this codebase.
 *
 * <p>The reason is not style. Binary floating point cannot represent 0.1 exactly, so
 * {@code 0.1 + 0.2 == 0.30000000000000004} — and errors like that compound across a
 * ledger until the books do not balance. "Where did the $0.03 go" is unanswerable and
 * an audit failure. Integers have none of this: cents are counted, never approximated.
 *
 * <p>A {@code long} holds ~92 quadrillion cents, so overflow is not a practical concern
 * at any balance this system will hold. {@link #requirePositive} exists for a likelier
 * failure: a zero or negative deposit is a caller bug, and it must be rejected at the
 * edge rather than quietly appended as a permanent fact.
 */
public final class Money {

    private Money() {}

    /**
     * Guards the sign of an amount before it reaches the domain. A negative deposit
     * would be a withdrawal wearing the wrong name — the log must say what actually
     * happened, so the two never blur.
     */
    public static long requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "amountMinor must be positive, got: " + amountMinor);
        }
        return amountMinor;
    }
}
