package dev.reckon.command.application;

import dev.reckon.command.domain.account.Account;
import dev.reckon.command.domain.account.AccountCommand;
import dev.reckon.command.domain.account.AccountEvent;
import dev.reckon.command.eventstore.ConcurrencyConflictException;
import java.util.List;
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

    public AccountCommandHandler(AccountRepository repository) {
        this.repository = repository;
    }

    /**
     * @return the account as of the appended events
     * @throws ConcurrencyRetriesExhaustedException if contention outlasted every attempt
     */
    public Account handle(AccountCommand command) {
        return execute(command.accountId(), account -> account.decide(command));
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
                return account;
            } catch (ConcurrencyConflictException conflict) {
                log.debug("conflict on {} at version {}, attempt {}/{}: reloading and deciding again",
                        accountId, expectedVersion, attempt, MAX_ATTEMPTS);
                backOff(attempt);
            }
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
