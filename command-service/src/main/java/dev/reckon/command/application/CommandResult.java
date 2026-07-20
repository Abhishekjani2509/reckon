package dev.reckon.command.application;

import dev.reckon.command.domain.account.Account;

/**
 * The application-level result of a single-aggregate command: enough to build the API
 * response, and small enough to store as the idempotency record so a retry can replay it.
 *
 * <p>Kept separate from the API's response DTO so the application layer does not depend on
 * the web layer, and separate from {@link Account} so the stored result is a flat value,
 * not a rehydrated aggregate.
 */
public record CommandResult(
        String accountId,
        String owner,
        String currency,
        long balanceMinor,
        long version
) {
    static CommandResult from(Account account) {
        return new CommandResult(
                account.accountId(), account.owner(), account.currency(),
                account.balanceMinor(), account.version());
    }
}
