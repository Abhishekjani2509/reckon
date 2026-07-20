package dev.reckon.projection.projection;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reckon.projection.consumer.EventEnvelope;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one event to the Postgres read models — the balance and the transaction history
 * — in a single transaction.
 *
 * <p>The two writes are atomic together: a balance change and its history row commit as
 * one, so the models can never disagree about whether an event was recorded. Redis is
 * intentionally <em>not</em> written here; that happens after this transaction commits, so
 * Redis is only ever updated for a durably-applied event.
 *
 * <p>Returns the resulting snapshot when the event applied, empty when the balance guard
 * rejected it as a duplicate — the projector uses that to decide whether to refresh Redis.
 */
@Component
public class AccountReadModel {

    private static final Logger log = LoggerFactory.getLogger(AccountReadModel.class);

    private final AccountBalanceRepository balances;
    private final AccountTransactionRepository transactions;

    public AccountReadModel(AccountBalanceRepository balances, AccountTransactionRepository transactions) {
        this.balances = balances;
        this.transactions = transactions;
    }

    @Transactional
    public Optional<BalanceSnapshot> apply(EventEnvelope event) {
        return switch (event.eventType()) {
            case "AccountOpened" -> applyOpened(event);
            case "MoneyDeposited" -> applyDelta(event, amountMinor(event));
            case "MoneyWithdrawn" -> applyDelta(event, -amountMinor(event));

            // Transfer money movements: a debit and compensation on the source, a credit on
            // the destination. Each moves the balance and is recorded in history.
            case "MoneyDebited" -> applyDelta(event, -amountMinor(event));
            case "MoneyCredited" -> applyDelta(event, amountMinor(event));
            case "TransferCompensated" -> applyDelta(event, amountMinor(event));

            // Transfer markers: no money moves, but the version MUST still advance, or the
            // account's next real event would fail the projection's version guard and stall.
            // Advanced without a history row -- history shows the money movements, not the
            // saga's bookkeeping.
            case "TransferInitiated", "TransferCompleted" -> advanceMarker(event);

            default -> {
                log.warn("no projection for event type {}; skipping v{} of {}",
                        event.eventType(), event.version(), event.aggregateId());
                yield Optional.empty();
            }
        };
    }

    private Optional<BalanceSnapshot> applyOpened(EventEnvelope event) {
        Optional<BalanceSnapshot> snapshot = balances.insertOpened(
                event.aggregateId(),
                event.payload().get("owner").asText(),
                event.payload().get("currency").asText());
        // The opening entry: zero amount, zero balance. Recorded only on first apply, so
        // the history has exactly one open row per account.
        snapshot.ifPresent(s -> transactions.insert(
                s.accountId(), event.version(), event.eventType(), 0L, s.balanceMinor(),
                s.currency(), occurredAt(event), idempotencyKey(event)));
        return snapshot;
    }

    private Optional<BalanceSnapshot> applyDelta(EventEnvelope event, long signedAmount) {
        Optional<BalanceSnapshot> snapshot = balances.applyDelta(
                event.aggregateId(), signedAmount, event.version());
        snapshot.ifPresent(s -> transactions.insert(
                s.accountId(), event.version(), event.eventType(), signedAmount, s.balanceMinor(),
                s.currency(), occurredAt(event), idempotencyKey(event)));
        return snapshot;
    }

    /** Advance the version for a balance-neutral marker; no history row. */
    private Optional<BalanceSnapshot> advanceMarker(EventEnvelope event) {
        return balances.advanceVersion(event.aggregateId(), event.version());
    }

    private static long amountMinor(EventEnvelope event) {
        return event.payload().get("amountMinor").asLong();
    }

    private static String idempotencyKey(EventEnvelope event) {
        JsonNode key = event.payload().get("idempotencyKey");
        return key == null || key.isNull() ? null : key.asText();
    }

    private static Instant occurredAt(EventEnvelope event) {
        return Instant.parse(event.occurredAt());
    }
}
