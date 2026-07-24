package dev.reckon.projection.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.reckon.projection.projection.BalanceProjector;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
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

    /**
     * Projection lag: the wall-clock gap between an event happening on the write side
     * ({@code occurredAt}) and this service applying it to the read model — the single
     * best measure of how stale reads are. It is the eventual-consistency window made
     * numeric: near-zero means the read model is tracking writes; a rising lag means the
     * consumer is falling behind the stream. Recorded as a histogram so Grafana can show
     * p50/p99, not just an average that hides the tail.
     */
    private final Timer projectionLag;

    public AccountEventConsumer(ObjectMapper objectMapper, BalanceProjector projector, MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.projector = projector;
        this.projectionLag = Timer.builder("reckon.projection.lag")
                .description("Time from an event occurring on the write side to it being projected")
                .publishPercentileHistogram()
                .register(meters);
    }

    @KafkaListener(topics = "${reckon.projection.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(String message) {
        EventEnvelope envelope = deserialize(message);
        projector.project(envelope);
        // Measure after the projection commits: the lag we care about is time-to-visible,
        // which includes the DB write, not merely time-to-received. occurredAt is an
        // Instant.toString() on the wire, so it round-trips through Instant.parse.
        projectionLag.record(Duration.between(Instant.parse(envelope.occurredAt()), Instant.now()));
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
