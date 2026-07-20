package dev.reckon.command.application;

/**
 * A command result plus whether it was replayed from the idempotency store rather than
 * freshly applied.
 *
 * <p>{@code replayed} is surfaced to the caller as a response header. The result is
 * identical either way — that is the point of idempotency — but a caller (and the demo)
 * benefits from knowing a retry was recognised rather than re-executed.
 */
public record CommandOutcome(CommandResult result, boolean replayed) {}
