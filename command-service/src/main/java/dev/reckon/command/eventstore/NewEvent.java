package dev.reckon.command.eventstore;

/**
 * An event on its way into the store, already serialised.
 *
 * <p>No version: the store assigns that from the caller's expected version, so a caller
 * cannot invent one and sidestep the concurrency check.
 *
 * @param eventType the permanent type name recorded in the {@code event_type} column
 * @param payloadJson the event body, stored as JSONB
 */
public record NewEvent(String eventType, String payloadJson) {}
