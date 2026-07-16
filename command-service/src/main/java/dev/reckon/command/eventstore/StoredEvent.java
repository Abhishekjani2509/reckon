package dev.reckon.command.eventstore;

import java.time.Instant;
import java.util.UUID;

/**
 * An event as it exists in the log: the envelope plus its serialised body.
 *
 * @param sequenceNumber global append order across all aggregates
 * @param version per-aggregate version, contiguous from 1
 * @param occurredAt when the fact happened, distinct from when the store accepted it
 */
public record StoredEvent(
        long sequenceNumber,
        UUID eventId,
        String aggregateId,
        String aggregateType,
        long version,
        String eventType,
        String payloadJson,
        Instant occurredAt
) {}
