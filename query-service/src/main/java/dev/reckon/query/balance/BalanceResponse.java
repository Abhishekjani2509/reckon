package dev.reckon.query.balance;

/**
 * A balance as served to a caller.
 *
 * @param version the read-model version this balance reflects — lets a client that just
 *     issued a command tell whether the read side has caught up to its write yet
 */
public record BalanceResponse(
        String accountId,
        long balanceMinor,
        String currency,
        long version
) {}
