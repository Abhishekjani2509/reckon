package dev.reckon.projection.projection;

import dev.reckon.projection.consumer.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Folds account events into the account_balances read model.
 *
 * <p>The mirror of the write-side aggregate's {@code apply}: it turns an event into a
 * balance change and nothing more. It makes no decisions and enforces no invariants —
 * the overdraft rule already ran on the write side before this event ever existed. A
 * projector that re-validated would be trying to veto the past.
 *
 * <p>Idempotency lives entirely in the repository's guarded SQL, so this class can be a
 * plain translation from event to delta.
 */
@Component
public class BalanceProjector {

    private static final Logger log = LoggerFactory.getLogger(BalanceProjector.class);

    private final AccountBalanceRepository repository;

    public BalanceProjector(AccountBalanceRepository repository) {
        this.repository = repository;
    }

    public void project(EventEnvelope event) {
        switch (event.eventType()) {
            case "AccountOpened" -> repository.insertOpened(
                    event.aggregateId(),
                    event.payload().get("owner").asText(),
                    event.payload().get("currency").asText());

            case "MoneyDeposited" -> repository.applyDelta(
                    event.aggregateId(), amountMinor(event), event.version());

            case "MoneyWithdrawn" -> repository.applyDelta(
                    event.aggregateId(), -amountMinor(event), event.version());

            // Transfer events are defined in the contract but not produced until the saga
            // exists. Skip rather than crash the consumer: an unhandled type here would
            // wedge the partition on retry. They will be projected when transfers land.
            default -> log.warn("no projection for event type {}; skipping v{} of {}",
                    event.eventType(), event.version(), event.aggregateId());
        }
    }

    private static long amountMinor(EventEnvelope event) {
        return event.payload().get("amountMinor").asLong();
    }
}
