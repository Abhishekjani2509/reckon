package dev.reckon.projection.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * projection-service's view of the event contract carried on the Kafka topic.
 *
 * <p>Deliberately <b>not</b> shared code with command-service — the two services agree on
 * a wire format, not a class. This record redeclares only the envelope fields the read
 * side needs, and treats {@code payload} as an opaque {@link JsonNode} whose fields each
 * projector interprets for itself.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is the versioning safety valve:
 * when the write side adds a field to the envelope, this consumer keeps working instead
 * of failing to deserialise. Tolerant readers are what let the two services evolve on
 * their own schedules.
 *
 * @param version the per-aggregate version — the projector's idempotency key
 * @param payload the event body, shaped differently per event type
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        String eventId,
        String aggregateId,
        String aggregateType,
        long version,
        String eventType,
        String occurredAt,
        JsonNode payload
) {}
