package dev.reckon.projection.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reckon.projection.projection.BalanceProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the account event stream and hands each event to the projectors.
 *
 * <p>One listener, subscribed to the account-events topic. Because the topic is keyed by
 * aggregate id, every event for a given account lands on one partition and is delivered
 * in version order — which is exactly the guarantee the projector's version guard relies
 * on. Distinct accounts spread across partitions and are processed in parallel.
 *
 * <p>The listener does not catch exceptions on purpose. Offsets commit only after it
 * returns normally (ack-mode: record), so a failed projection leaves the offset
 * uncommitted and the event is redelivered — safe, because the projection is idempotent.
 * Deserialisation failures are equally loud: an unparseable message is a broken contract,
 * not something to skip past into a silent hole in the read model.
 */
@Component
public class AccountEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final BalanceProjector projector;

    public AccountEventConsumer(ObjectMapper objectMapper, BalanceProjector projector) {
        this.objectMapper = objectMapper;
        this.projector = projector;
    }

    @KafkaListener(topics = "${reckon.projection.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(String message) {
        EventEnvelope envelope = deserialize(message);
        projector.project(envelope);
        log.debug("projected {} v{} for {}", envelope.eventType(), envelope.version(), envelope.aggregateId());
    }

    private EventEnvelope deserialize(String message) {
        try {
            return objectMapper.readValue(message, EventEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException("could not deserialise event envelope: " + message, e);
        }
    }
}
