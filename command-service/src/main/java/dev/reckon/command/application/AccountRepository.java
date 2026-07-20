package dev.reckon.command.application;

import dev.reckon.command.domain.account.Account;
import dev.reckon.command.domain.account.AccountEvent;
import dev.reckon.command.eventstore.EventStore;
import dev.reckon.command.eventstore.NewEvent;
import dev.reckon.command.eventstore.ProcessedCommand;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Loads and saves {@link Account} aggregates, bridging domain types and the generic
 * event store.
 *
 * <p>"Repository" in the loosest sense: nothing here stores an account. {@link #load}
 * reads events and folds them; {@link #append} writes events. The aggregate is only ever
 * a computation over the log.
 */
@Repository
public class AccountRepository {

    private static final String AGGREGATE_TYPE = "Account";

    private final EventStore eventStore;
    private final AccountEventJsonCodec codec;

    public AccountRepository(EventStore eventStore, AccountEventJsonCodec codec) {
        this.eventStore = eventStore;
        this.codec = codec;
    }

    /**
     * Reads the account's whole history and folds it into current state.
     *
     * <p>An account with no events folds to "not opened" rather than null — history
     * simply has nothing to say about it, which is a state the domain can reason about.
     */
    public Account load(String accountId) {
        List<AccountEvent> history = eventStore.loadStream(accountId).stream()
                .map(stored -> codec.fromJson(stored.eventType(), stored.payloadJson()))
                .toList();
        return Account.rehydrate(accountId, history);
    }

    /**
     * @param expectedVersion the version the caller's decision was folded from; the
     *     append is rejected if the aggregate has moved on since
     * @throws dev.reckon.command.eventstore.ConcurrencyConflictException if another
     *     writer claimed the next version first
     */
    public void append(String accountId, long expectedVersion, List<AccountEvent> events) {
        eventStore.append(accountId, AGGREGATE_TYPE, expectedVersion, serialise(events));
    }

    /**
     * Appends events and records an idempotency row atomically.
     *
     * @throws dev.reckon.command.eventstore.DuplicateCommandException if the key was
     *     already recorded for this account
     * @throws dev.reckon.command.eventstore.ConcurrencyConflictException if another
     *     writer claimed the next version first
     */
    public void append(String accountId, long expectedVersion, List<AccountEvent> events, ProcessedCommand idempotency) {
        eventStore.append(accountId, AGGREGATE_TYPE, expectedVersion, serialise(events), idempotency);
    }

    private List<NewEvent> serialise(List<AccountEvent> events) {
        return events.stream()
                .map(event -> new NewEvent(codec.typeName(event), codec.toJson(event)))
                .toList();
    }
}
