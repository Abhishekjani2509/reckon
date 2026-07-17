package dev.reckon.command.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Enqueues an event for publication by inserting an outbox row.
 *
 * <p>Called from within the event store's append, so the insert runs in the <b>same
 * transaction</b> as the event it describes. That shared transaction is the whole point:
 * the event and its outbox row commit together or not at all, which is what makes
 * publishing consistent with the log without a distributed transaction.
 *
 * <p>Owns the Kafka message format so that neither the event store nor the poller has to.
 * The poller reads the finished envelope and sends it verbatim.
 */
@Component
public class OutboxWriter {

    private static final String INSERT_OUTBOX = """
            INSERT INTO outbox (event_id, aggregate_id, topic, payload)
            VALUES (?, ?, ?, ?::jsonb)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper objectMapper, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Builds the event envelope and stores it as an unpublished outbox row.
     *
     * <p>The envelope mirrors the stored event exactly — same {@code eventId}, same
     * {@code occurredAt} — because both are passed the identical values the event row was
     * written with. A projector reading the topic sees precisely what the log holds.
     */
    public void enqueue(UUID eventId, String aggregateId, String aggregateType,
                        long version, String eventType, String payloadJson, Instant occurredAt) {
        String envelope = buildEnvelope(eventId, aggregateId, aggregateType, version, eventType, payloadJson, occurredAt);
        jdbc.update(INSERT_OUTBOX, eventId, aggregateId, properties.topic(), envelope);
    }

    private String buildEnvelope(UUID eventId, String aggregateId, String aggregateType,
                                 long version, String eventType, String payloadJson, Instant occurredAt) {
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("eventId", eventId.toString());
            envelope.put("aggregateId", aggregateId);
            envelope.put("aggregateType", aggregateType);
            envelope.put("version", version);
            envelope.put("eventType", eventType);
            envelope.put("occurredAt", occurredAt.toString());
            // set, not put: payloadJson is already JSON and must be embedded as a nested
            // object, not re-escaped into a string.
            envelope.set("payload", objectMapper.readTree(payloadJson));
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not build outbox envelope for event " + eventId, e);
        }
    }
}
