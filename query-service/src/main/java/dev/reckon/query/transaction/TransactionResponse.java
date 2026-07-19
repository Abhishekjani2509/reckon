package dev.reckon.query.transaction;

import java.time.Instant;

/**
 * One transaction in an account's history.
 *
 * @param version the per-account version — also this row's pagination cursor
 * @param amountMinor signed: positive credit, negative debit, zero for the opening entry
 * @param balanceAfter the account balance immediately after this transaction
 */
public record TransactionResponse(
        long version,
        String eventType,
        long amountMinor,
        long balanceAfter,
        String currency,
        Instant occurredAt
) {}
