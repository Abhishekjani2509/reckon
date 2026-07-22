package dev.reckon.command.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reckon.command.domain.account.Account;
import dev.reckon.command.domain.account.AccountEvent;
import dev.reckon.command.domain.account.AccountSnapshot;
import dev.reckon.command.eventstore.EventStore;
import dev.reckon.command.eventstore.NewEvent;
import dev.reckon.command.eventstore.ProcessedCommand;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AccountRepository.class);

    private final EventStore eventStore;
    private final AccountEventJsonCodec codec;
    private final SnapshotStore snapshotStore;
    private final ObjectMapper objectMapper;

    public AccountRepository(EventStore eventStore, AccountEventJsonCodec codec,
                            SnapshotStore snapshotStore, ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.codec = codec;
        this.snapshotStore = snapshotStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Folds the account into current state, from its latest snapshot when one exists.
     *
     * <p>With a snapshot: reconstruct from it and replay only the events after its version.
     * Without one: replay the whole history. Either path yields identical state — the
     * snapshot only bounds how many events are read. An account with no events folds to
     * "not opened" rather than null.
     */
    public Account load(String accountId) {
        Optional<StoredSnapshot> snapshot = snapshotStore.findLatest(accountId);

        if (snapshot.isPresent()) {
            long fromVersion = snapshot.get().version();
            Account account = Account.fromSnapshot(accountId, readSnapshot(snapshot.get().stateJson()), fromVersion);
            List<AccountEvent> later = readEvents(eventStore.loadStreamAfter(accountId, fromVersion));
            account.applyAll(later);
            log.debug("loaded {} from snapshot v{}, applied {} later events", accountId, fromVersion, later.size());
            return account;
        }

        List<AccountEvent> history = readEvents(eventStore.loadStream(accountId));
        log.debug("loaded {} by full replay of {} events (no snapshot)", accountId, history.size());
        return Account.rehydrate(accountId, history);
    }

    /** Persists the aggregate's folded state as its latest snapshot. */
    public void saveSnapshot(Account account) {
        snapshotStore.save(account.accountId(), account.version(), writeSnapshot(account.snapshotState()));
    }

    private List<AccountEvent> readEvents(List<dev.reckon.command.eventstore.StoredEvent> stored) {
        return stored.stream()
                .map(e -> codec.fromJson(e.eventType(), e.payloadJson()))
                .toList();
    }

    private AccountSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, AccountSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("could not read snapshot state: " + json, e);
        }
    }

    private String writeSnapshot(AccountSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise snapshot state", e);
        }
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
