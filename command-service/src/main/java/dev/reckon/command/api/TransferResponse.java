package dev.reckon.command.api;

import dev.reckon.command.application.TransferOutcome;

/**
 * The outcome of a transfer.
 *
 * <p>status is COMPLETED when funds moved and COMPENSATED when a step failed and the debit
 * was reversed. In the compensated case failureReason explains what went wrong and
 * sourceBalanceMinor shows the source restored — the caller can see its money is safe.
 */
public record TransferResponse(
        String transferId,
        String status,
        String sourceAccountId,
        String destinationAccountId,
        long amountMinor,
        String currency,
        long sourceBalanceMinor,
        String failureReason
) {
    static TransferResponse from(TransferOutcome outcome) {
        return new TransferResponse(
                outcome.transferId(),
                outcome.status().name(),
                outcome.sourceAccountId(),
                outcome.destinationAccountId(),
                outcome.amountMinor(),
                outcome.currency(),
                outcome.sourceBalanceMinor(),
                outcome.failureReason());
    }
}
