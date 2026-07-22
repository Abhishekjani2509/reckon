package dev.reckon.command.application;

import dev.reckon.command.domain.account.Account;
import dev.reckon.command.domain.account.AccountCommand;
import dev.reckon.command.domain.account.AccountEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reckon.command.eventstore.ConcurrencyConflictException;
import dev.reckon.command.eventstore.DuplicateCommandException;
import dev.reckon.command.eventstore.ProcessedCommand;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs a command against an account: load, decide, append — retrying the whole cycle if
 * a concurrent writer gets there first.
 *
 * <p>Note this class is <b>not</b> {@code @Transactional}, which is deliberate and load
 * bearing. The transaction belongs to a single append attempt (inside the event store),
 * because a constraint violation aborts the transaction it happened in — nothing further
 * can be done inside it. Making this method transactional would wrap every retry in one
 * doomed transaction and the loop could never succeed.
 */
@Service
public class AccountCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountCommandHandler.class);

    /**
     * Writers contending for one account are effectively serialised: each round produces
     * exactly one winner, so N concurrent writers can need up to N rounds. The bound has
     * to leave room for that, while still failing eventually rather than spinning forever
     * under pathological load — a caller waiting on a lost cause is worse than a clear
     * error it can retry.
     */
    private static final int MAX_ATTEMPTS = 12;

    /**
     * Retrying instantly is what turns a conflict into a thundering herd: every loser
     * collides again in lockstep, and the same writers keep losing. Backing off spreads
     * them out so each round has a winner.
     *
     * <p>Full jitter — sleep a random duration in {@code [0, base * 2^attempt)} rather
     * than the ceiling itself. Backoff without randomness keeps collided writers
     * synchronised, so they simply collide again later, together.
     *
     * <p>Sleeping a request thread is normally wasteful; on virtual threads it is not,
     * since a sleeping virtual thread releases its carrier rather than parking an OS
     * thread.
     */
    private static final long BASE_BACKOFF_MILLIS = 2;

    private final AccountRepository repository;
    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;
    private final int snapshotInterval;

    public AccountCommandHandler(AccountRepository repository, IdempotencyStore idempotencyStore,
                                 ObjectMapper objectMapper,
                                 @org.springframework.beans.factory.annotation.Value("${reckon.snapshot.interval}") int snapshotInterval) {
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
        this.objectMapper = objectMapper;
        this.snapshotInterval = snapshotInterval;
    }

    /**
     * Saves a snapshot when the appended versions crossed a snapshot boundary.
     *
     * <p>Off the critical path and non-fatal: a snapshot is only an optimisation, so a
     * failure here is logged and swallowed — the command already succeeded, and the worst
     * outcome is that the next load replays a few more events. The boundary check uses
     * integer division rather than {@code version % interval == 0} so a transfer's
     * two-version jump cannot skip a boundary.
     */
    private void maybeSnapshot(Account account, long oldVersion) {
        long newVersion = account.version();
        boolean crossedBoundary = oldVersion / snapshotInterval < newVersion / snapshotInterval;
        if (!crossedBoundary) {
            return;
        }
        try {
            repository.saveSnapshot(account);
            log.info("saved snapshot for {} at version {}", account.accountId(), newVersion);
        } catch (RuntimeException e) {
            log.warn("failed to save snapshot for {} at version {}: {}",
                    account.accountId(), newVersion, e.getMessage());
        }
    }

    /**
     * Runs a command idempotently: if its key was already applied to the account, the
     * stored result is replayed; otherwise it is applied and recorded atomically with its
     * events.
     *
     * @return the result and whether it was replayed from a prior application
     */
    public CommandOutcome handle(AccountCommand command) {
        String accountId = command.accountId();
        String key = command.idempotencyKey();

        // Fast path: a key we have already seen returns its stored result with no reload,
        // decide, or append. This is the common case for a client retry.
        Optional<CommandResult> replay = idempotencyStore.find(accountId, key).map(this::readResult);
        if (replay.isPresent()) {
            return new CommandOutcome(replay.get(), true);
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Account account = repository.load(accountId);
            long expectedVersion = account.version();

            List<AccountEvent> newEvents = account.decide(command);
            account.applyAll(newEvents);
            CommandResult result = CommandResult.from(account);

            try {
                repository.append(accountId, expectedVersion, newEvents,
                        new ProcessedCommand(key, writeResult(result)));
                maybeSnapshot(account, expectedVersion);
                return new CommandOutcome(result, false);
            } catch (ConcurrencyConflictException conflict) {
                log.debug("conflict on {} at version {}, attempt {}/{}: reloading and deciding again",
                        accountId, expectedVersion, attempt, MAX_ATTEMPTS);
                backOff(attempt);
            } catch (DuplicateCommandException duplicate) {
                // A concurrent identical command committed the key between our fast-path
                // check and this append. It is the winner; we replay its stored result.
                return new CommandOutcome(
                        readResult(idempotencyStore.find(accountId, key).orElseThrow()), true);
            }
        }
        throw new ConcurrencyRetriesExhaustedException(accountId, MAX_ATTEMPTS);
    }

    private CommandResult readResult(String json) {
        try {
            return objectMapper.readValue(json, CommandResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("could not read stored command result: " + json, e);
        }
    }

    private String writeResult(CommandResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialise command result", e);
        }
    }

    /**
     * Loads an aggregate, applies a decision to it, and appends the result — retrying the
     * whole cycle on a concurrent-write conflict.
     *
     * <p>This is the reusable core of the write side. A plain command is one decision on
     * one aggregate; a transfer saga is several such decisions across two aggregates,
     * composed by {@code TransferSaga}. Both go through here, so both get the same
     * optimistic-concurrency guarantee.
     *
     * @param decide reads the loaded aggregate and returns the events to append, or throws
     *     a domain exception to reject. Called afresh on every retry — see below.
     */
    public Account execute(String accountId, Function<Account, List<AccountEvent>> decide) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {

            // Reload and re-fold on every attempt. This is the important part: a retry
            // must re-run the DECISION, not just the write. The writer that beat us may
            // have moved the balance, and a withdrawal that was valid against $50 must be
            // refused against $10. Re-appending the previously decided events would append
            // facts the domain would no longer authorise — reintroducing by hand exactly
            // the double-spend the unique constraint just prevented.
            Account account = repository.load(accountId);
            long expectedVersion = account.version();

            // May throw a domain exception (overdraft, unknown account). Those are the
            // caller's answer and must not be retried — retrying changes nothing, since
            // the decision is invalid against the state that exists.
            List<AccountEvent> newEvents = decide.apply(account);

            try {
                repository.append(accountId, expectedVersion, newEvents);
                account.applyAll(newEvents);
                maybeSnapshot(account, expectedVersion);
                return account;
            } catch (ConcurrencyConflictException conflict) {
                log.debug("conflict on {} at version {}, attempt {}/{}: reloading and deciding again",
                        accountId, expectedVersion, attempt, MAX_ATTEMPTS);
                backOff(attempt);
            }
        }
        throw new ConcurrencyRetriesExhaustedException(accountId, MAX_ATTEMPTS);
    }

    /**
     * Like {@link #execute}, but records an idempotency key with the append. Used by the
     * transfer saga to stamp the client's key onto the source atomically with the debit, so
     * a retried transfer is caught at its first step and never debits twice.
     *
     * <p>Unlike {@link #handle}, this does not swallow a duplicate: a
     * {@link DuplicateCommandException} propagates to the saga, which decides how to
     * respond (return the in-flight or finalised outcome). {@code resultJson} is fixed by
     * the caller — a transfer's placeholder — because it does not depend on the debited
     * balance.
     */
    public Account executeRecording(String accountId, String idempotencyKey, String resultJson,
                                    Function<Account, List<AccountEvent>> decide) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Account account = repository.load(accountId);
            long expectedVersion = account.version();
            List<AccountEvent> newEvents = decide.apply(account);
            try {
                repository.append(accountId, expectedVersion, newEvents,
                        new ProcessedCommand(idempotencyKey, resultJson));
                account.applyAll(newEvents);
                maybeSnapshot(account, expectedVersion);
                return account;
            } catch (ConcurrencyConflictException conflict) {
                backOff(attempt);
            }
            // DuplicateCommandException deliberately propagates to the saga.
        }
        throw new ConcurrencyRetriesExhaustedException(accountId, MAX_ATTEMPTS);
    }

    /** Full-jitter exponential backoff: a random wait in {@code [0, base * 2^attempt)}. */
    private static void backOff(int attempt) {
        long ceilingMillis = BASE_BACKOFF_MILLIS << Math.min(attempt - 1, 6);
        long delayMillis = ThreadLocalRandom.current().nextLong(ceilingMillis);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it: something is trying to shut this
            // thread down, and a retry loop that ignores interruption cannot be stopped.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off before retry", e);
        }
    }

    /** Sustained contention on one account beat every attempt. Retryable by the caller. */
    public static class ConcurrencyRetriesExhaustedException extends RuntimeException {
        public ConcurrencyRetriesExhaustedException(String accountId, int attempts) {
            super("gave up appending to %s after %d attempts under concurrent writes"
                    .formatted(accountId, attempts));
        }
    }
}
