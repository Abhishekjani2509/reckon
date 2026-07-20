package dev.reckon.command.application;

import dev.reckon.command.domain.account.Account;
import dev.reckon.command.domain.account.AccountCommand;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a transfer as a saga: a sequence of single-aggregate steps across the
 * source and destination accounts, with compensation if a step fails.
 *
 * <p>There is no distributed transaction here and no two-phase commit. Each step is its
 * own append, committed on its own. What makes the whole safe is not atomicity across the
 * two accounts — it is that a failed credit is followed by a compensating credit back to
 * the source, so money is never created or destroyed even though the steps commit
 * separately.
 *
 * <p>The steps in order:
 * <ol>
 *   <li>debit the source (with the overdraft check) — atomically with the initiation marker;</li>
 *   <li>credit the destination;</li>
 *   <li>mark the source complete.</li>
 * </ol>
 * If the credit cannot be committed for any reason, the source is compensated and the
 * transfer is reported as failed-but-consistent.
 */
@Service
public class TransferSaga {

    private static final Logger log = LoggerFactory.getLogger(TransferSaga.class);

    private final AccountCommandHandler handler;

    public TransferSaga(AccountCommandHandler handler) {
        this.handler = handler;
    }

    public TransferOutcome execute(AccountCommand.Transfer command) {
        String transferId = "txf_" + UUID.randomUUID();
        String source = command.accountId();
        String destination = command.destinationAccountId();
        long amount = command.amountMinor();
        String currency = command.currency();
        String key = command.idempotencyKey();

        // Step 1: initiate + debit the source. A domain failure here (unknown source,
        // wrong currency, overdraft, self-transfer) rejects the transfer outright — no
        // money has moved, so there is nothing to compensate; the exception propagates.
        handler.execute(source, account ->
                account.decideTransferDebit(transferId, destination, amount, currency, key + ":debit"));

        // Step 2: credit the destination. This is the step that can fail after money has
        // already left the source.
        try {
            handler.execute(destination, account ->
                    account.decideTransferCredit(transferId, amount, currency, key + ":credit"));
        } catch (RuntimeException creditFailure) {
            // The credit did not commit (a thrown step never appended), so the debit stands
            // alone and must be reversed. Compensate for ANY failure to credit -- a missing
            // destination, a currency mismatch, or contention exhausting retries all leave
            // the source short and must be made whole.
            return compensate(transferId, source, destination, amount, currency, key, creditFailure);
        }

        // Step 3: mark the transfer complete on the source.
        Account completedSource = handler.execute(source, account ->
                account.decideTransferComplete(transferId, key + ":complete"));

        log.info("transfer {} completed: {} -> {} ({} minor)", transferId, source, destination, amount);
        return TransferOutcome.completed(transferId, source, destination, amount, currency,
                completedSource.balanceMinor());
    }

    private TransferOutcome compensate(String transferId, String source, String destination,
                                       long amount, String currency, String key, RuntimeException cause) {
        log.warn("transfer {} credit to {} failed ({}); compensating source {}",
                transferId, destination, cause.getMessage(), source);

        // Reverse the debit. This uses the same retry machinery as any append, so it is
        // robust to ordinary contention on the source. If compensation itself could not be
        // committed, the transfer is left in-flight (debited, uncompensated) -- see the
        // class note in the brief: recovery of a stuck saga, keyed off the TransferInitiated
        // marker with no terminal event, is deferred to the idempotency work.
        Account compensatedSource = handler.execute(source, account ->
                account.decideTransferCompensate(transferId, amount, currency, cause.getMessage(), key + ":compensate"));

        return TransferOutcome.compensated(transferId, source, destination, amount, currency,
                compensatedSource.balanceMinor(), cause.getMessage());
    }
}
