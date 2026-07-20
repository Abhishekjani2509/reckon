package dev.reckon.command.application;

/**
 * The result of running a transfer saga.
 *
 * <p>Two terminal outcomes, both consistent: {@code COMPLETED} (funds moved) and
 * {@code COMPENSATED} (a step failed, the debit was reversed, balances are back where they
 * belong). Compensated is not an error in the system — it is the saga working correctly on
 * a partial failure — but from the caller's view the transfer did not happen, so the
 * controller surfaces it as a failure with a reason.
 *
 * @param sourceBalanceMinor the source balance after the saga's terminal step, so the
 *     caller sees the effect without a separate read
 * @param failureReason why the transfer was compensated; null when completed
 */
public record TransferOutcome(
        String transferId,
        Status status,
        String sourceAccountId,
        String destinationAccountId,
        long amountMinor,
        String currency,
        long sourceBalanceMinor,
        String failureReason
) {
    public enum Status { COMPLETED, COMPENSATED }

    static TransferOutcome completed(String transferId, String source, String destination,
                                     long amount, String currency, long sourceBalance) {
        return new TransferOutcome(transferId, Status.COMPLETED, source, destination,
                amount, currency, sourceBalance, null);
    }

    static TransferOutcome compensated(String transferId, String source, String destination,
                                       long amount, String currency, long sourceBalance, String reason) {
        return new TransferOutcome(transferId, Status.COMPENSATED, source, destination,
                amount, currency, sourceBalance, reason);
    }

    public boolean isCompensated() {
        return status == Status.COMPENSATED;
    }
}
