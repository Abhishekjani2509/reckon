package dev.reckon.command.eventstore;

/**
 * A command's idempotency record, written into processed_commands in the same transaction
 * as the command's events.
 *
 * @param idempotencyKey the client-supplied key, unique per aggregate
 * @param resultJson the result to replay on a retry, serialised
 */
public record ProcessedCommand(String idempotencyKey, String resultJson) {}
