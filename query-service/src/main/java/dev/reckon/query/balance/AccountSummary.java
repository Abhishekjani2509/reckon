package dev.reckon.query.balance;

/** An account and its current balance, for the dashboard's account list. */
public record AccountSummary(
        String accountId,
        String owner,
        String currency,
        long balanceMinor,
        long version
) {}
