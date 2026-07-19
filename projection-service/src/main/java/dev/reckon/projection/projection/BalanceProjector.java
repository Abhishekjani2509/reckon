package dev.reckon.projection.projection;

import dev.reckon.projection.consumer.EventEnvelope;
import dev.reckon.projection.redis.HotBalanceStore;
import org.springframework.stereotype.Component;

/**
 * Projects an account event into the read models.
 *
 * <p>Two steps, in order: apply to Postgres in a transaction, then — only if the event
 * genuinely applied — write the resulting balance to Redis. Doing Redis after the DB
 * commit means the hot balance is never ahead of the durable read model, and a duplicate
 * event (which the balance guard rejects, yielding no snapshot) touches neither store.
 *
 * <p>The one honest edge: a crash between the DB commit and the Redis write leaves Redis
 * briefly stale until the account's next event. The query service's Postgres fallback
 * covers a missing key, and a production system would close the gap with write-through on
 * read or periodic reconciliation. It is called out rather than hidden.
 */
@Component
public class BalanceProjector {

    private final AccountReadModel readModel;
    private final HotBalanceStore hotBalance;

    public BalanceProjector(AccountReadModel readModel, HotBalanceStore hotBalance) {
        this.readModel = readModel;
        this.hotBalance = hotBalance;
    }

    public void project(EventEnvelope event) {
        readModel.apply(event).ifPresent(hotBalance::put);
    }
}
