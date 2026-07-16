package dev.reckon.command.api;

import dev.reckon.command.domain.account.Account;

/**
 * The account's state after a command was applied.
 *
 * <p>Returning state from a write is not a CQRS violation: this is the write model
 * reporting its own folded state, not a read model being queried. It gives callers
 * read-your-writes without waiting on a projection.
 *
 * @param version the aggregate version after the append — the count of events in this
 *     account's history, and a client's proof of where in the log it stands
 */
public record AccountResponse(
        String accountId,
        String owner,
        String currency,
        long balanceMinor,
        long version
) {
    static AccountResponse from(Account account) {
        return new AccountResponse(
                account.accountId(),
                account.owner(),
                account.currency(),
                account.balanceMinor(),
                account.version());
    }
}
