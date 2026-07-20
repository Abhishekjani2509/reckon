package dev.reckon.command.application;

/**
 * A transfer outcome plus whether it was replayed from the idempotency store rather than
 * freshly run. The transfer analogue of {@link CommandOutcome}: {@code replayed} becomes
 * the {@code Idempotent-Replayed} header, so a retried transfer is visibly recognised
 * rather than re-executed.
 */
public record TransferExecution(TransferOutcome outcome, boolean replayed) {}
