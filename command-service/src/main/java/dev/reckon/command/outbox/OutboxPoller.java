package dev.reckon.command.outbox;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the outbox to Kafka: read unpublished rows, publish them, mark them published.
 *
 * <p>This runs on a schedule, entirely separate from command handling. A command's job
 * ends when its transaction commits the event and its outbox row; getting that row onto
 * the topic is this poller's job, asynchronously. That separation is what keeps the write
 * path free of Kafka and its failures.
 *
 * <p><b>Delivery is at-least-once.</b> A row is marked published only after Kafka has
 * acknowledged it, so a crash between the send and the mark leaves the row unpublished and
 * it is sent again next pass. Duplicates are therefore possible across a crash, which is
 * why every consumer downstream must be idempotent.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    // FOR UPDATE SKIP LOCKED lets this be run by more than one instance safely: each pass
    // locks the rows it claims and skips rows another poller already holds, so no row is
    // published twice concurrently. With a single poller it is simply a cheap no-op.
    private static final String CLAIM_BATCH = """
            SELECT id, aggregate_id, topic, payload::text AS payload
            FROM outbox
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_PUBLISHED = "UPDATE outbox SET published_at = now() WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;

    public OutboxPoller(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.properties = properties;
    }

    /**
     * One drain pass, in a single transaction so the row locks from the claim are held
     * until the marks commit. Publishing happens inside that transaction on purpose: a row
     * is not released as "published" until Kafka has actually accepted it.
     */
    @Scheduled(fixedDelayString = "${reckon.outbox.poll-delay-ms}")
    @Transactional
    public void drain() {
        List<OutboxRow> batch = jdbc.query(CLAIM_BATCH,
                (rs, n) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getString("aggregate_id"),
                        rs.getString("topic"),
                        rs.getString("payload")),
                properties.batchSize());

        if (batch.isEmpty()) {
            return;
        }

        for (OutboxRow row : batch) {
            // Block on the ack. This is the "at-least-once" hinge: only a confirmed send
            // is allowed to mark the row published. A send that throws aborts the pass and
            // rolls the transaction back, leaving every row in it unpublished for retry.
            kafka.send(row.topic(), row.aggregateId(), row.payload()).join();
            jdbc.update(MARK_PUBLISHED, row.id());
        }

        log.debug("published {} outbox row(s)", batch.size());
    }

    private record OutboxRow(long id, String aggregateId, String topic, String payload) {}
}
