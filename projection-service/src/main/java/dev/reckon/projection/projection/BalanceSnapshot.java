package dev.reckon.projection.projection;

/**
 * The state of an account_balances row right after an event was applied. Returned by the
 * balance repository so the projector can write the same values to the transaction row and
 * to Redis without a second read.
 */
public record BalanceSnapshot(
        String accountId,
        long balanceMinor,
        String currency,
        long version
) {}
